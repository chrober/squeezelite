/**
 * Adapted from LMS-Material-App / android-squeezer
 * Apache-2.0 license
 */

package org.lyrion.squeezelite.cometd;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;

import org.lyrion.squeezelite.Utils;

public class BayeuxExtension extends ClientSession.Extension.Adapter {
    @Override
    public boolean sendMeta(ClientSession session, Message.Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            if (message.getClientId() != null) {
                Utils.verbose("Reset client id");
                message.setClientId(null);
            }
        }
        return true;
    }
}
