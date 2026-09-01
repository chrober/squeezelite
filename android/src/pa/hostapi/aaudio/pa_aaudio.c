/*
 * $Id$
 * PortAudio Portable Real-Time Audio Library
 * Latest Version at: http://www.portaudio.com
 *
 * Based on the Android OpenSL ES implementation by Sanne Raymaekers
 * Copyright (c) 2016-2017 Sanne Raymaekers <sanne.raymaekers@gmail.com>
 *
 * Based on the Open Source API proposed by Ross Bencina
 * Copyright (c) 1999-2002 Ross Bencina, Phil Burk
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files
 * (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

/*
 * The text above constitutes the entire PortAudio license; however,
 * the PortAudio community also makes the following non-binding requests:
 *
 * Any person wishing to distribute modifications to the Software is
 * requested to send the modifications to the original developer so that
 * they can be incorporated into the canonical version. It is also
 * requested that these non-binding requests be included along with the
 * license above.
 */

/**
 @file
 @ingroup hostapi_src
 @brief AAudio implementation of support for a host API.
*/

#include <android/log.h>
#include <android/api-level.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "libaaudio.h"
#include "pa_util.h"
#include "pa_allocation.h"
#include "pa_hostapi.h"
#include "pa_stream.h"
#include "pa_cpuload.h"
#include "pa_process.h"
#include "pa_unix_util.h"

int PaAAudio_ENABLED = 0;

static unsigned long nativeBufferSize = 0;

#define MODULE_NAME "PaAAudio"

#ifndef NDEBUG
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, MODULE_NAME, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, MODULE_NAME, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, MODULE_NAME, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,MODULE_NAME, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,MODULE_NAME, __VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL,MODULE_NAME, __VA_ARGS__)
#else
#define LOGV(...)
#define LOGD(...)
#define LOGI(...)
#define LOGW(...)
#define LOGE(...)
#define LOGF(...)
#endif

#define ENSURE(expr, errorText) \
    do { \
        PaError err; \
        if( UNLIKELY( (err = (expr)) < paNoError ) ) { \
            LOGE( "Expression '" #expr "' failed in '" __FILE__ "', line: " PA_STRINGIZE( __LINE__ )); \
            PaUtil_SetLastHostErrorInfo( paInDevelopment, err, errorText ); \
            result = err; \
            goto error; \
        } \
    } while(0);

typedef struct {
    PaUtilHostApiRepresentation inheritedHostApiRep;
    PaUtilStreamInterface callbackStreamInterface;
    PaUtilStreamInterface blockingStreamInterface;
    PaUtilAllocationGroup *allocations;
} PaAAudioHostApiRepresentation;

typedef struct {
    int use;
    AAudioStream *stream;
    int bytesPerSample;
    int channelCount;
    aaudio_format_t format;
    /* Total frames submitted to the device since start. */
    int64_t framesSubmitted;
    /* Position estimator: the HAL timestamp pair only refreshes every
     * ~100ms while callbacks arrive more often, so most callbacks see a
     * stale pair. We anchor on each fresh pair and extrapolate on our own
     * CLOCK_MONOTONIC between refreshes. halToOurNs is the HAL-to-local
     * clock offset, tracked as a running minimum of observed read-lag. */
    double anchorPos;
    double anchorOurS;
    int anchorValid;
    double slopeHz;
    int64_t prevPairPos;
    int64_t prevPairNs;
    int prevPairValid;
    int64_t halToOurNs;
    int halToOurValid;
} PaAAStream;

typedef struct {
    PaUtilStreamRepresentation streamRepresentation;
    PaUtilCpuLoadMeasurer cpuLoadMeasurer;
    PaUtilBufferProcessor bufferProcessor;

    volatile int isStopped;
    volatile int isActive;

    PaStreamCallbackFlags cbFlags;
    unsigned long framesPerHostCallback;

    PaAAStream output;
    PaAAStream input;
} PaAAudioStream;

static unsigned long GetApproximateLowBufferSize() {
    return __ANDROID_API__ <= 23 ? 256 : 192;
}

static PaError PaSampleFormatToAAudioFormat(PaSampleFormat format, aaudio_format_t *aaudioFormat) {
    switch (format) {
        case paInt16:
            *aaudioFormat = AAUDIO_FORMAT_PCM_I16;
            return paNoError;
        case paInt24:
            *aaudioFormat = AAUDIO_FORMAT_PCM_I24_PACKED;
            return paNoError;
        case paFloat32:
            *aaudioFormat = AAUDIO_FORMAT_PCM_FLOAT;
            return paNoError;
        default:
            return paSampleFormatNotSupported;
    }
}

static PaError IsOutputSampleRateSupported(PaAAudioHostApiRepresentation *hostApi, double sampleRate) {
    (void)hostApi;
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *stream = NULL;
    if (LibAAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGE("IsOutputSampleRateSupported %f - LibAAudio_createStreamBuilder failed", sampleRate);
        return paUnanticipatedHostError;
    }
    LibAAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    LibAAudioStreamBuilder_setSampleRate(builder, (int)sampleRate);
    if (LibAAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK || !stream) {
        LibAAudioStreamBuilder_delete(builder);
        LOGE("IsOutputSampleRateSupported %f - LibAAudioStreamBuilder_openStream failed", sampleRate);
        return paInvalidSampleRate;
    }
    LibAAudioStream_close(stream);
    LibAAudioStreamBuilder_delete(builder);
    LOGD("IsOutputSampleRateSupported %f SUCCESS", sampleRate);
    return paNoError;
}

static PaError IsInputSampleRateSupported(PaAAudioHostApiRepresentation *hostApi, double sampleRate) {
    (void)hostApi;
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *stream = NULL;
    if (LibAAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGE("IsInputSampleRateSupported %f - LibAAudio_createStreamBuilder failed", sampleRate);
        return paUnanticipatedHostError;
    }
    LibAAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    LibAAudioStreamBuilder_setSampleRate(builder, (int)sampleRate);
    if (LibAAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK || !stream) {
        LibAAudioStreamBuilder_delete(builder);
        LOGE("IsInputSampleRateSupported %f - LibAAudioStreamBuilder_openStream failed", sampleRate);
        return paInvalidSampleRate;
    }
    LibAAudioStream_close(stream);
    LibAAudioStreamBuilder_delete(builder);
    LOGD("IsInputSampleRateSupported %f SUCCESS", sampleRate);
    return paNoError;
}

static PaError IsOutputChannelCountSupported(PaAAudioHostApiRepresentation *hostApi, int numOfChannels) {
    (void)hostApi;
    if (numOfChannels > 2 || numOfChannels == 0) {
        LOGE("IsOutputChannelCountSupported %d - paInvalidChannelCount", numOfChannels);
        return paInvalidChannelCount;
    }
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *stream = NULL;
    if (LibAAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGE("IsOutputChannelCountSupported %d - LibAAudio_createStreamBuilder failed", numOfChannels);
        return paUnanticipatedHostError;
    }
    LibAAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    LibAAudioStreamBuilder_setChannelCount(builder, numOfChannels);
    if (LibAAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK || !stream) {
        LibAAudioStreamBuilder_delete(builder);
        LOGE("IsOutputChannelCountSupported %d - LibAAudioStreamBuilder_openStream failed", numOfChannels);
        return paInvalidChannelCount;
    }
    LibAAudioStream_close(stream);
    LibAAudioStreamBuilder_delete(builder);
    LOGD("IsOutputChannelCountSupported %d SUCCESS", numOfChannels);
    return paNoError;
}

static PaError IsInputChannelCountSupported(PaAAudioHostApiRepresentation *hostApi, int numOfChannels) {
    (void)hostApi;
    if (numOfChannels > 2 || numOfChannels == 0) {
        LOGE("IsInputChannelCountSupported %d - paInvalidChannelCount", numOfChannels);
        return paInvalidChannelCount;
    }
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *stream = NULL;
    if (LibAAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGE("IsInputChannelCountSupported %d - LibAAudio_createStreamBuilder failed", numOfChannels);
        return paUnanticipatedHostError;
    }
    LibAAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    LibAAudioStreamBuilder_setChannelCount(builder, numOfChannels);
    if (LibAAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK || !stream) {
        LibAAudioStreamBuilder_delete(builder);
        LOGE("IsInputChannelCountSupported %d - LibAAudioStreamBuilder_openStream failed", numOfChannels);
        return paInvalidChannelCount;
    }
    LibAAudioStream_close(stream);
    LibAAudioStreamBuilder_delete(builder);
    LOGD("IsInputChannelCountSupported %d SUCCESS", numOfChannels);
    return paNoError;
}

/* Fill timeInfo so that consumers can derive the device queue depth:
 *   device_frames = (outputBufferDacTime - currentTime) * sampleRate
 * AAudioStream_getTimestamp gives frames played and when; combined with
 * framesSubmitted this yields the queued depth. If the timestamp is
 * unavailable (pre-API-26, route changes), fall back to getFramesRead,
 * then to a zeroed timeInfo. */
static void FillTimeInfo(PaAAudioStream *aaudioStream, AAudioStream *stream,
                         int32_t numFrames, PaStreamCallbackTimeInfo *timeInfo) {
    static const double NS_PER_S = 1000000000.0;
    static int cbCount = 0;
    int src = 0; /* 0=none 1=timestamp 2=framesRead */
    int64_t framePosition = 0;
    int64_t timeNanos = 0;
    double sampleRate = aaudioStream->streamRepresentation.streamInfo.sampleRate;

    if (sampleRate <= 0) {
        return;
    }

    aaudioStream->output.framesSubmitted += numFrames;

    if (LibAAudio_HasTimestamp() &&
        LibAAudioStream_getTimestamp(stream, CLOCK_MONOTONIC,
                                     &framePosition, &timeNanos) == AAUDIO_OK &&
        framePosition >= 0) {
        src = 1;
    } else if (LibAAudioStream_getFramesRead(stream, &framePosition) == AAUDIO_OK &&
               LibAAudioStream_getTimeNanos(stream, &timeNanos) == AAUDIO_OK &&
               framePosition >= 0) {
        src = 2;
    } else {
        src = 0;
    }

    if (0 != src) {
        double rawQueued = (double)(aaudioStream->output.framesSubmitted) - (double)framePosition;
        if (rawQueued < 0) {
            rawQueued = 0; /* timestamp raced/reset */
        }

        /* Anchor on fresh pairs, extrapolate between them on our own
         * clock. Never advance on fabricated time. */
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        double ourS = (double)ts.tv_sec + (double)ts.tv_nsec / NS_PER_S;
        int64_t ourNs = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
        int pairFresh = !aaudioStream->output.prevPairValid ||
                        framePosition != aaudioStream->output.prevPairPos ||
                        timeNanos != aaudioStream->output.prevPairNs;

        /* Observed read-lag = offset + pair age (age >= 0), so the true
         * offset is a lower bound of every observation. */
        int64_t lagNs = ourNs - timeNanos;
        if (!aaudioStream->output.halToOurValid || lagNs < aaudioStream->output.halToOurNs) {
            aaudioStream->output.halToOurNs = lagNs;
            aaudioStream->output.halToOurValid = 1;
        }
        if (pairFresh) {
            /* Consumption rate since the previous fresh pair. Require the
             * position to advance so a time-only refresh cannot zero the
             * slope. */
            if (aaudioStream->output.prevPairValid &&
                framePosition > aaudioStream->output.prevPairPos &&
                timeNanos > aaudioStream->output.prevPairNs + 100000000LL /*100ms*/) {
                double s = (double)(framePosition - aaudioStream->output.prevPairPos) *
                           NS_PER_S / (double)(timeNanos - aaudioStream->output.prevPairNs);
                /* Clamp so a bad pair can skew at most one window. */
                if (s > sampleRate * 1.1) s = sampleRate * 1.1;
                if (s < sampleRate * 0.9) s = sampleRate * 0.9;
                aaudioStream->output.slopeHz = s;
            }
            aaudioStream->output.prevPairPos = framePosition;
            aaudioStream->output.prevPairNs = timeNanos;
            aaudioStream->output.prevPairValid = 1;
            /* Anchor at the pair's capture instant in our time base. */
            aaudioStream->output.anchorPos = (double)framePosition;
            aaudioStream->output.anchorOurS =
                ((double)timeNanos + (double)aaudioStream->output.halToOurNs) / NS_PER_S;
            if (!aaudioStream->output.anchorValid) {
                /* Seed the slope until a pair-to-pair interval exists. */
                aaudioStream->output.slopeHz = sampleRate;
                aaudioStream->output.anchorValid = 1;
            }
        }
        if (!aaudioStream->output.anchorValid) {
            return; /* nothing trustworthy yet: keep zeroed timeInfo */
        }
        double dt = ourS - aaudioStream->output.anchorOurS;
        if (dt < 0) dt = 0;
        double playedEst = aaudioStream->output.anchorPos + dt * aaudioStream->output.slopeHz;

        /* output_pa.c stamps frames_played_dmp at callback start, i.e.
         * framesSubmitted - numFrames. Report the depth against the same
         * count so the two cancel in ms_played regardless of burst sizes. */
        double queuedFrames = (double)(aaudioStream->output.framesSubmitted - numFrames) - playedEst;
        if (queuedFrames < 0) queuedFrames = 0;

        timeInfo->currentTime = ourS;
        timeInfo->outputBufferDacTime = ourS + queuedFrames / sampleRate;
        if ((++cbCount % 250) == 0) {
            LOGD("syncdbg src=%d submitted=%lld played=%lld queuedMs=%.0f rawMs=%.0f estMs=%.0f slope=%.1f lagMs=%.1f dt=%.3f",
                 src,
                 (long long)aaudioStream->output.framesSubmitted,
                 (long long)framePosition,
                 1000.0 * queuedFrames / sampleRate,
                 1000.0 * rawQueued / sampleRate,
                 1000.0 * playedEst / sampleRate,
                 aaudioStream->output.slopeHz,
                 (double)aaudioStream->output.halToOurNs / 1000000.0,
                 dt);
        }
        return;
    }

    /* No timing source: leave zeroed. */
    if ((++cbCount % 250) == 0) {
        LOGD("syncdbg src=0 (no timing source)");
    }
}

static aaudio_data_callback_result_t AaudioDataCallback(AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
    PaAAudioStream *aaudioStream = (PaAAudioStream *)userData;
    PaStreamCallbackTimeInfo timeInfo = {0, 0, 0};
    int result = paContinue;
    unsigned long framesProcessed = 0;

    if (aaudioStream->output.use) {
        FillTimeInfo(aaudioStream, stream, numFrames, &timeInfo);
    }

    PaUtil_BeginBufferProcessing(&aaudioStream->bufferProcessor, &timeInfo, aaudioStream->cbFlags);
    PaUtil_SetOutputFrameCount(&aaudioStream->bufferProcessor, numFrames);
    PaUtil_SetInterleavedOutputChannels(&aaudioStream->bufferProcessor, 0, audioData, 0);
    framesProcessed = PaUtil_EndBufferProcessing(&aaudioStream->bufferProcessor, &result);

    if (framesProcessed < (unsigned long)numFrames) {
        // Zero the rest of the buffer for safety.
        unsigned char *p = (unsigned char *)audioData;
        memset(p + framesProcessed * aaudioStream->output.bytesPerSample * aaudioStream->output.channelCount, 0,
               (numFrames - framesProcessed) * aaudioStream->output.bytesPerSample * aaudioStream->output.channelCount);
    }

    LOGD("AaudioDataCallback %d RESULT: %d", numFrames, result);

    if (result != paContinue) {
        aaudioStream->isActive = 0;
        aaudioStream->isStopped = 1;
        if( aaudioStream->streamRepresentation.streamFinishedCallback != NULL ) {
            aaudioStream->streamRepresentation.streamFinishedCallback( aaudioStream->streamRepresentation.userData );
        }
        // Returning STOP races the AAudioStream_close() that follows the finished
        // callback, and can deadlock inside AAudio. The app stops the stream.
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void AaudioErrorCallback(AAudioStream *stream, void *userData, aaudio_result_t error) {
    LOGE("AaudioErrorCallback: %d", (int)error);
    if (AAUDIO_ERROR_DISCONNECTED==error || (AAUDIO_ERROR_TIMEOUT==error && __ANDROID_API__==__ANDROID_API_R__)) {
        LOGD("Reopen");
        PaAAudioStream *aaudioStream = (PaAAudioStream *)userData;
        aaudioStream->isActive = 0;
        aaudioStream->isStopped = 1;
        if( aaudioStream->streamRepresentation.streamFinishedCallback != NULL ) {
            aaudioStream->streamRepresentation.streamFinishedCallback( aaudioStream->streamRepresentation.userData );
        }
    }
}

static PaError CloseStream(PaStream *s) {
    LOGD("CloseStream");
    PaAAudioStream *aaudioStream = (PaAAudioStream *)s;
    if (aaudioStream->output.use && aaudioStream->output.stream) {
        LibAAudioStream_close(aaudioStream->output.stream);
    }
    if (aaudioStream->input.use && aaudioStream->input.stream) {
        LibAAudioStream_close(aaudioStream->input.stream);
    }
    PaUtil_TerminateBufferProcessor(&aaudioStream->bufferProcessor);
    PaUtil_TerminateStreamRepresentation(&aaudioStream->streamRepresentation);
    free(aaudioStream);
    return paNoError;
}

static PaError StartStream(PaStream *s) {
    LOGD("StartStream");
    PaAAudioStream *aaudioStream = (PaAAudioStream *)s;
    PaUtil_ResetBufferProcessor(&aaudioStream->bufferProcessor);
    aaudioStream->isStopped = 0;
    aaudioStream->isActive = 1;
    /* Queued-depth bookkeeping starts fresh with the stream. */
    aaudioStream->output.framesSubmitted = 0;
    aaudioStream->output.anchorValid = 0;
    aaudioStream->output.prevPairValid = 0;
    aaudioStream->output.halToOurValid = 0;
    aaudioStream->output.slopeHz = 0;
    if (aaudioStream->output.use && aaudioStream->output.stream && LibAAudioStream_requestStart(aaudioStream->output.stream) != AAUDIO_OK) {
        return paUnanticipatedHostError;
    }
    if (aaudioStream->input.use && aaudioStream->input.stream && LibAAudioStream_requestStart(aaudioStream->input.stream) != AAUDIO_OK) {
        return paUnanticipatedHostError;
    }
    return paNoError;
}

static PaError StopStream(PaStream *s) {
    LOGD("StopStream");
    PaAAudioStream *aaudioStream = (PaAAudioStream *)s;
    aaudioStream->isActive = 0;
    aaudioStream->isStopped = 1;
    if (aaudioStream->output.use && aaudioStream->output.stream && LibAAudioStream_requestStop(aaudioStream->output.stream) != AAUDIO_OK) {
        return paUnanticipatedHostError;
    }
    if (aaudioStream->input.use && aaudioStream->input.stream && LibAAudioStream_requestStop(aaudioStream->input.stream) != AAUDIO_OK) {
        return paUnanticipatedHostError;
    }
    return paNoError;
}

static PaError AbortStream(PaStream *s) {
    LOGD("AbortStream");
    return StopStream(s);
}

static PaError IsStreamStopped(PaStream *s) {
    return ((PaAAudioStream *)s)->isStopped;
}

static PaError IsStreamActive(PaStream *s) {
    return ((PaAAudioStream *)s)->isActive;
}

static PaTime GetStreamTime(PaStream *s) {
    return PaUtil_GetTime();
}

static double GetStreamCpuLoad(PaStream *s) {
    return PaUtil_GetCpuLoad(&((PaAAudioStream *)s)->cpuLoadMeasurer);
}

static PaError ReadStream(PaStream *s, void *buffer, unsigned long frames) {
    PaAAudioStream *aaudioStream = (PaAAudioStream *)s;
    if (!aaudioStream->input.use){
        return paBadStreamPtr;
    }
    int32_t result = LibAAudioStream_read(
        aaudioStream->input.stream,
        buffer,
        frames,
        1000000 /* 1 second timeout in ns */
    );
    return result < 0 ? paUnanticipatedHostError : paNoError;
}

static PaError WriteStream(PaStream *s, const void *buffer, unsigned long frames) {
    PaAAudioStream *aaudioStream = (PaAAudioStream *)s;
    if (!aaudioStream->output.use) {
        return paBadStreamPtr;
    }
    int32_t result = LibAAudioStream_write(
        aaudioStream->output.stream,
        buffer,
        frames,
        1000000 /* 1 second timeout in ns */
    );
    return result < 0 ? paUnanticipatedHostError : paNoError;
}

static signed long GetStreamReadAvailable(PaStream *s) {
    return 0;
}

static signed long GetStreamWriteAvailable(PaStream *s) {
    return 0;
}

static PaError OpenStream(struct PaUtilHostApiRepresentation *hostApi, PaStream **s, const PaStreamParameters *inputParameters,
                          const PaStreamParameters *outputParameters, double sampleRate, unsigned long framesPerBuffer,
                          PaStreamFlags streamFlags, PaStreamCallback *streamCallback, void *userData ) {
    LOGD("OpenStream framesPerBuffer:%d", framesPerBuffer);
    PaError result = paNoError;
    PaAAudioHostApiRepresentation *aaudioHostApi = (PaAAudioHostApiRepresentation*)hostApi;
    PaAAudioStream *aaudioStream = NULL;
    unsigned long framesPerHostBuffer = 0;

    int inputChannelCount = 0, outputChannelCount = 0;
    PaSampleFormat inputSampleFormat = paInt16, outputSampleFormat = paInt16;
    PaSampleFormat hostInputSampleFormat = paInt16, hostOutputSampleFormat = paInt16;
    aaudio_format_t inputAaudioFormat = AAUDIO_FORMAT_PCM_I16, outputAaudioFormat = AAUDIO_FORMAT_PCM_I16;

    if (!streamCallback) {
        LOGE("Blocking mode not supported");
        goto error;
    }
 
    if (inputParameters) {
        inputChannelCount = inputParameters->channelCount;
        inputSampleFormat = inputParameters->sampleFormat;
        if (inputParameters->device == paUseHostApiSpecificDeviceSpecification) {
            return paInvalidDevice;
        }
        if (inputChannelCount > hostApi->deviceInfos[inputParameters->device]->maxInputChannels) {
            return paInvalidChannelCount;
        }
        hostInputSampleFormat = PaUtil_SelectClosestAvailableFormat(paInt16 | paFloat32, inputSampleFormat);
        ENSURE(PaSampleFormatToAAudioFormat(hostInputSampleFormat, &inputAaudioFormat), "Unsupported input sample format");
    }
    if (outputParameters) {
        outputChannelCount = outputParameters->channelCount;
        outputSampleFormat = outputParameters->sampleFormat;
        if (outputParameters->device == paUseHostApiSpecificDeviceSpecification) {
            return paInvalidDevice;
        }
        if (outputChannelCount > hostApi->deviceInfos[outputParameters->device]->maxOutputChannels) {
            return paInvalidChannelCount;
        }
        hostOutputSampleFormat = PaUtil_SelectClosestAvailableFormat(paInt16 | paFloat32 | (__ANDROID_API__>=31 ? paInt24 : 0), outputSampleFormat);
        ENSURE(PaSampleFormatToAAudioFormat(hostOutputSampleFormat, &outputAaudioFormat), "Unsupported output sample format");
    }

    if ((streamFlags & paPlatformSpecificFlags) != 0) {
        return paInvalidFlag;
    }

    if (framesPerBuffer == paFramesPerBufferUnspecified) {
        if (outputParameters) {
            framesPerHostBuffer = (unsigned long)(outputParameters->suggestedLatency * sampleRate);
        } else if (inputParameters) {
            framesPerHostBuffer = (unsigned long)(inputParameters->suggestedLatency * sampleRate);
        } else {
            framesPerHostBuffer = GetApproximateLowBufferSize();
        }
    } else {
        framesPerHostBuffer = framesPerBuffer;
    }

    aaudioStream = (PaAAudioStream*)calloc(1, sizeof(PaAAudioStream));
    if (!aaudioStream) {
        result = paInsufficientMemory;
        goto error;
    }

    PaUtil_InitializeStreamRepresentation(&aaudioStream->streamRepresentation, &aaudioHostApi->callbackStreamInterface,
                                          streamCallback, userData);
    PaUtil_InitializeCpuLoadMeasurer(&aaudioStream->cpuLoadMeasurer, sampleRate);

    /* The buffer processor is initialized below, after the streams are
     * opened, so the user/host buffer sizes can follow the actual
     * frames-per-burst. */

    aaudioStream->streamRepresentation.streamInfo.sampleRate = sampleRate;
    aaudioStream->framesPerHostCallback = framesPerHostBuffer;
    aaudioStream->cbFlags = 0;
    aaudioStream->isStopped = 1;
    aaudioStream->isActive = 0;
    aaudioStream->input.use = (inputChannelCount > 0);
    aaudioStream->output.use = (outputChannelCount > 0);

    // Output stream setup
    if (aaudioStream->output.use) {
        AAudioStreamBuilder *builder = NULL;
        aaudioStream->output.bytesPerSample = Pa_GetSampleSize(hostOutputSampleFormat);
        aaudioStream->output.channelCount = outputChannelCount;
        aaudioStream->output.format = outputAaudioFormat;

        if (LibAAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
            result = paUnanticipatedHostError;
            LOGE("OpenStream/output - LibAAudio_createStreamBuilder failed");
            goto error;
        }
        LibAAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        LibAAudioStreamBuilder_setChannelCount(builder, outputChannelCount);
        LibAAudioStreamBuilder_setSampleRate(builder, (int)sampleRate);
        LibAAudioStreamBuilder_setFormat(builder, outputAaudioFormat);
        if (__ANDROID_API__>=26) {
            LibAAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        }
        if (__ANDROID_API__>=28) {
            LibAAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
            LibAAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        }
        LibAAudioStreamBuilder_setDataCallback(builder, AaudioDataCallback, aaudioStream);
        LibAAudioStreamBuilder_setErrorCallback(builder, AaudioErrorCallback, aaudioStream);

        if (LibAAudioStreamBuilder_openStream(builder, &aaudioStream->output.stream) != AAUDIO_OK || !aaudioStream->output.stream) {
            LibAAudioStreamBuilder_delete(builder);
            result = paUnanticipatedHostError;
            goto error;
        }
        LibAAudioStreamBuilder_delete(builder);
        LOGD("syncdbg open: framesPerBuffer=%lu hostCalc=%lu burst=%d",
             framesPerBuffer, framesPerHostBuffer,
             LibAAudioStream_getFramesPerBurst(aaudioStream->output.stream));
    }

    // Input stream setup
    if (aaudioStream->input.use) {
        AAudioStreamBuilder *builder = NULL;
        aaudioStream->input.bytesPerSample = Pa_GetSampleSize(hostInputSampleFormat);
        aaudioStream->input.channelCount = inputChannelCount;
        aaudioStream->input.format = inputAaudioFormat;

        if (LibAAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
            result = paUnanticipatedHostError;
            LOGE("OpenStream/input - LibAAudio_createStreamBuilder failed");
            goto error;
        }
        LibAAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
        LibAAudioStreamBuilder_setChannelCount(builder, inputChannelCount);
        LibAAudioStreamBuilder_setSampleRate(builder, (int)sampleRate);
        LibAAudioStreamBuilder_setFormat(builder, inputAaudioFormat);

        // TODO: Set input callback if needed for full duplex

        if (LibAAudioStreamBuilder_openStream(builder, &aaudioStream->input.stream) != AAUDIO_OK || !aaudioStream->input.stream) {
            LibAAudioStreamBuilder_delete(builder);
            result = paUnanticipatedHostError; goto error;
        }
        LibAAudioStreamBuilder_delete(builder);
    }

    /* When no buffer size was requested, use one user callback per AAudio
     * callback so the buffer processor does not split host callbacks. */
    {
        unsigned long userFrames = framesPerBuffer;
        unsigned long hostFrames = framesPerHostBuffer;
        if (userFrames == paFramesPerBufferUnspecified && aaudioStream->output.use) {
            int32_t burst = aaudioStream->output.stream
                ? LibAAudioStream_getFramesPerBurst(aaudioStream->output.stream) : -1;
            if (burst > 0) {
                userFrames = (unsigned long)burst;
                hostFrames = (unsigned long)burst;
            }
        }
        result = PaUtil_InitializeBufferProcessor(&aaudioStream->bufferProcessor, inputChannelCount, inputSampleFormat,
                                                  hostInputSampleFormat, outputChannelCount, outputSampleFormat,
                                                  hostOutputSampleFormat, sampleRate, streamFlags, userFrames,
                                                  hostFrames, paUtilFixedHostBufferSize, streamCallback, userData);
        if (result != paNoError) {
            goto error;
        }
        aaudioStream->framesPerHostCallback = hostFrames;
    }

    *s = (PaStream *)aaudioStream;
    return paNoError;

error:
    if (aaudioStream) {
        CloseStream((PaStream *)aaudioStream);
    }
    return result;
}

static PaError IsFormatSupported(struct PaUtilHostApiRepresentation *hostApi, const PaStreamParameters *inputParameters,
                                 const PaStreamParameters *outputParameters, double sampleRate) {
    LOGD("IsFormatSupported");
    PaAAudioHostApiRepresentation *aaudioHostApi = (PaAAudioHostApiRepresentation*) hostApi;
    int inputChannelCount = 0, outputChannelCount = 0;

    if (inputParameters) {
        inputChannelCount = inputParameters->channelCount;
        if (inputParameters->sampleFormat & paCustomFormat) {
            return paSampleFormatNotSupported;
        }
        if (inputParameters->device == paUseHostApiSpecificDeviceSpecification) {
            return paInvalidDevice;
        }
        if (inputChannelCount > hostApi->deviceInfos[inputParameters->device]->maxInputChannels) {
            return paInvalidChannelCount;
        }
    }
    if (outputParameters) {
        outputChannelCount = outputParameters->channelCount;
        if (outputParameters->sampleFormat & paCustomFormat) {
            return paSampleFormatNotSupported;
        }
        if (outputParameters->device == paUseHostApiSpecificDeviceSpecification) {
            return paInvalidDevice;
        }
        if (outputChannelCount > hostApi->deviceInfos[outputParameters->device]->maxOutputChannels) {
            return paInvalidChannelCount;
        }
    }
    if (outputChannelCount > 0 && IsOutputSampleRateSupported(aaudioHostApi, sampleRate) != paNoError) {
        return paInvalidSampleRate;
    }
    if (inputChannelCount > 0 && IsInputSampleRateSupported(aaudioHostApi, sampleRate) != paNoError) {
        return paInvalidSampleRate;
    }
    return paFormatIsSupported;
}

static void Terminate(struct PaUtilHostApiRepresentation *hostApi) {
    LOGD("Terminate");
    PaAAudioHostApiRepresentation *aaudioHostApi = (PaAAudioHostApiRepresentation*)hostApi;
    if (aaudioHostApi->allocations) {
        PaUtil_FreeAllAllocations(aaudioHostApi->allocations);
        PaUtil_DestroyAllocationGroup(aaudioHostApi->allocations);
    }
    free(aaudioHostApi);
}

PaError PaAAudio_Initialize(PaUtilHostApiRepresentation **hostApi, PaHostApiIndex hostApiIndex) {
    LOGD("PaAAudio_Initialize");
    PaError result = paNoError;
    PaAAudioHostApiRepresentation *aaudioHostApi;
    PaDeviceInfo *deviceInfoArray;

    aaudioHostApi = (PaAAudioHostApiRepresentation*)calloc(1, sizeof(PaAAudioHostApiRepresentation));
    if (!aaudioHostApi) {
        result = paInsufficientMemory;
        goto error;
    }

    aaudioHostApi->allocations = PaUtil_CreateAllocationGroup();
    if (!aaudioHostApi->allocations) {
        result = paInsufficientMemory;
        goto error;
    }

    *hostApi = &aaudioHostApi->inheritedHostApiRep;
    (*hostApi)->info.structVersion = 1;
    (*hostApi)->info.type = paInDevelopment;
    (*hostApi)->info.name = "android AAudio";
    (*hostApi)->info.defaultOutputDevice = 0;
    (*hostApi)->info.defaultInputDevice = 0;
    (*hostApi)->info.deviceCount = 0;

    if (!PaAAudio_ENABLED) {
        return paNoError;
    }

    int deviceCount = 1;
    (*hostApi)->deviceInfos = (PaDeviceInfo**)PaUtil_GroupAllocateZeroInitializedMemory(aaudioHostApi->allocations, sizeof(PaDeviceInfo*) * deviceCount);
    if (!(*hostApi)->deviceInfos) {
        result = paInsufficientMemory;
        goto error;
    }

    deviceInfoArray = (PaDeviceInfo*)PaUtil_GroupAllocateZeroInitializedMemory(aaudioHostApi->allocations, sizeof(PaDeviceInfo) * deviceCount);
    if (!deviceInfoArray) {
        result = paInsufficientMemory;
        goto error;
    }

    for (int i = 0; i < deviceCount; ++i) {
        PaDeviceInfo *deviceInfo = &deviceInfoArray[i];
        deviceInfo->structVersion = 2;
        deviceInfo->hostApi = hostApiIndex;
        deviceInfo->name = "default";
        const int channelsToTry[] = { 2, 1 };
        deviceInfo->maxOutputChannels = 0;
        deviceInfo->maxInputChannels = 0;
        for (int j = 0; j < 2; ++j) {
            if (IsOutputChannelCountSupported(aaudioHostApi, channelsToTry[j]) == paNoError) {
                deviceInfo->maxOutputChannels = channelsToTry[j];
                break;
            }
        }
        /*
        for (int j = 0; j < 2; ++j) {
            if (IsInputChannelCountSupported(aaudioHostApi, channelsToTry[j]) == paNoError) {
                deviceInfo->maxInputChannels = channelsToTry[j];
                break;
            }
        }
        */
        const int sampleRates[] = { 384000, 192000, 96000, 48000, 44100, 32000, 24000, 16000 };
        deviceInfo->defaultSampleRate = 0;
        for (int j = 0; j < 5; ++j) {
            if (IsOutputSampleRateSupported(aaudioHostApi, sampleRates[j]) == paNoError /*&&
                IsInputSampleRateSupported(aaudioHostApi, sampleRates[j]) == paNoError*/) {
                deviceInfo->defaultSampleRate = sampleRates[j];
                break;
            }
        }
        if (deviceInfo->defaultSampleRate == 0) {
            goto error;
        }

        if (nativeBufferSize) {
            deviceInfo->defaultLowInputLatency = (double)nativeBufferSize / deviceInfo->defaultSampleRate;
            deviceInfo->defaultLowOutputLatency = (double)nativeBufferSize / deviceInfo->defaultSampleRate;
            deviceInfo->defaultHighInputLatency = (double)nativeBufferSize * 4 / deviceInfo->defaultSampleRate;
            deviceInfo->defaultHighOutputLatency = (double)nativeBufferSize * 4 / deviceInfo->defaultSampleRate;
        } else {
            deviceInfo->defaultLowInputLatency = (double)GetApproximateLowBufferSize() / deviceInfo->defaultSampleRate;
            deviceInfo->defaultLowOutputLatency = (double)GetApproximateLowBufferSize() / deviceInfo->defaultSampleRate;
            deviceInfo->defaultHighInputLatency = (double)GetApproximateLowBufferSize() * 4 / deviceInfo->defaultSampleRate;
            deviceInfo->defaultHighOutputLatency = (double)GetApproximateLowBufferSize() * 4 / deviceInfo->defaultSampleRate;
        }
        (*hostApi)->deviceInfos[i] = deviceInfo;
        ++(*hostApi)->info.deviceCount;
    }

    (*hostApi)->Terminate = Terminate;
    (*hostApi)->OpenStream = OpenStream;
    (*hostApi)->IsFormatSupported = IsFormatSupported;

    PaUtil_InitializeStreamInterface(&aaudioHostApi->callbackStreamInterface, CloseStream, StartStream,
                                     StopStream, AbortStream, IsStreamStopped, IsStreamActive,
                                     GetStreamTime, GetStreamCpuLoad,
                                     PaUtil_DummyRead, PaUtil_DummyWrite,
                                     PaUtil_DummyGetReadAvailable, PaUtil_DummyGetWriteAvailable);

    PaUtil_InitializeStreamInterface(&aaudioHostApi->blockingStreamInterface, CloseStream, StartStream,
                                     StopStream, AbortStream, IsStreamStopped, IsStreamActive,
                                     GetStreamTime, PaUtil_DummyGetCpuLoad,
                                     ReadStream, WriteStream, GetStreamReadAvailable, GetStreamWriteAvailable);
    return result;
error:
    if (aaudioHostApi) {
        if (aaudioHostApi->allocations) {
            PaUtil_FreeAllAllocations(aaudioHostApi->allocations);
            PaUtil_DestroyAllocationGroup(aaudioHostApi->allocations);
        }
        free(aaudioHostApi);
    }
    return result;
}
