package com.jme3.network.plugins.quakemonkey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.jme3.network.AbstractMessage;
import com.jme3.network.Client;
import com.jme3.network.Message;
import com.jme3.network.MessageListener;
import com.jme3.network.base.MessageListenerRegistry;
import com.jme3.network.base.MessageProtocol;
import com.jme3.network.serializing.Serializer;

/**
 * Handles the client-side job of receiving either messages of type {@code T} or
 * delta messages. If a delta message is received, it is merged with a cached
 * old message. When the message is processed, an acknowledgment is sent to the
 * server.
 * <p>
 * Client can register message listeners for type {@code T} by calling
 * {@link #addListener()}. It is very important that the client does not listen
 * to message type {@code T} through other methods (for example directly from
 * the server).
 * <p>
 * * Important: make sure that you call
 * {@link DiffClassRegistration#registerClasses()} before starting the client.
 *
 * @author Ben Ruijl
 *
 * @param <T>
 *            Message type
 */
@SuppressWarnings("unchecked")
public class ClientDiffHandler<T extends AbstractMessage> implements
        MessageListener<Client> {

    protected static final Logger log = Logger.getLogger(ClientDiffHandler.class.getName());
    private final short numSnapshots;
    private final Class<T> cls;
    private final List<T> snapshots;
    private final MessageListenerRegistry<Client> listenerRegistry;
    private short curPos;

    public ClientDiffHandler(Client client, Class<T> cls, short numSnapshots) {
        this.numSnapshots = numSnapshots;
        this.cls = cls;
        listenerRegistry = new MessageListenerRegistry<Client>();
        snapshots = new ArrayList<T>(numSnapshots);

        for (int i = 0; i < numSnapshots; i++) {
            snapshots.add(null);
        }

        client.addMessageListener(this, LabeledMessage.class);
    }

    public void addListener(MessageListener<? super Client> listener) {
        listenerRegistry.addMessageListener(listener);
    }

    public void removeListener(MessageListener<? super Client> listener) {
        listenerRegistry.removeMessageListener(listener);
    }

    /**
     * Applies the delta message to the old message to generate a new message of
     * type {@code T}.
     *
     * @param oldMessage
     *            The old message
     * @param diffMessage
     *            The delta message
     * @return A new message of type {@code T}
     */
    public T mergeMessage(T oldMessage, DiffMessage diffMessage) {
        ByteBuffer oldBuffer = MessageProtocol.messageToBuffer(oldMessage, null);

        /* Copy old message */
        ByteBuffer newBuffer = ByteBuffer.allocate(32767);
        newBuffer.put(oldBuffer);
        newBuffer.position(0);

        int index = 0;
        for (int i = 0; i < 8 * diffMessage.getFlag().length; i++) {
            if ((diffMessage.getFlag()[i / 8] & (1 << (i % 8))) != 0) {
                newBuffer.putInt(i * 4, diffMessage.getData()[index]);
                index++;
            }
        }

        try {
            newBuffer.position(2); // skip size
            return (T) Serializer.readClassAndObject(newBuffer);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not merge messages", e);
        }

        return null;
    }

    /**
     * Process the arrival of either a message of type {@code T} or a delta
     * message. Sends an acknowledgment to the server when a message is
     * received.
     */
    @Override
    public void messageReceived(Client source, Message m) {
        if (m instanceof LabeledMessage) {
            LabeledMessage lm = (LabeledMessage) m;
            T message = (T) lm.getMessage();

            boolean isNew = curPos < lm.getLabel()
                    || lm.getLabel() - curPos > Short.MAX_VALUE / 2;

            // message is too old
            if (curPos - lm.getLabel() > numSnapshots
                    || (lm.getLabel() - curPos > Short.MAX_VALUE / 2 && Short.MAX_VALUE
                    - lm.getLabel() + curPos > numSnapshots)) {
                log.log(Level.INFO, "Discarding too old message: {0} vs. cur {1}", new Object[]{lm.getLabel(), curPos});
                return;
            }

            if (cls.isInstance(lm.getMessage())) { // received full message
                snapshots.set(lm.getLabel() % numSnapshots, message);
            } else {
                if (lm.getMessage() instanceof DiffMessage) {
                    log.log(Level.FINE, "Received diff of size {0}", MessageProtocol.messageToBuffer(message, null).limit());

                    DiffMessage diffMessage = (DiffMessage) message;

                    T newMessage = mergeMessage(
                            snapshots.get(diffMessage.getMessageId()
                            % numSnapshots), diffMessage);

                    snapshots.set(lm.getLabel() % numSnapshots, newMessage);
                }
            }

            /* Send an ACK back */
            source.send(new AckMessage(lm.getLabel()));

            /* Broadcast changes */
            if (isNew) {
                curPos = lm.getLabel();
                listenerRegistry.messageReceived(source,
                        snapshots.get(curPos % numSnapshots));
            } else {
                // notify if message was old, for testing
                log.log(Level.FINEST, "Old message received: {0} vs. cur {1}", new Object[]{lm.getLabel(), curPos});
            }

        }
    }
}
