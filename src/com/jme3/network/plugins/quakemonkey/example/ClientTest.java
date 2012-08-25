package com.jme3.network.plugins.quakemonkey.example;

import java.io.IOException;
import com.jme3.network.Client;
import com.jme3.network.Message;
import com.jme3.network.MessageListener;
import com.jme3.network.Network;
import com.jme3.network.plugins.quakemonkey.ClientDiffHandler;
import com.jme3.network.plugins.quakemonkey.DiffClassRegistration;
import com.jme3.network.serializing.Serializer;

/**
 * An example client that shows how the snapshot network code works.
 * 
 * @author Ben Ruijl
 * 
 */
public class ClientTest implements MessageListener<Client> {

    final ClientDiffHandler<GameStateMessage> diffHandler;

    public ClientTest() throws IOException {
        DiffClassRegistration.registerClasses();
        Serializer.registerClass(GameStateMessage.class);

        Client myClient = Network.connectToServer("localhost", 6143);

        diffHandler = new ClientDiffHandler<GameStateMessage>(myClient, GameStateMessage.class, (short) 30);
        diffHandler.addListener(this); // register listener for GameStateMessage

        myClient.start();

        try {
            Thread.sleep(10000000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        myClient.close();
    }

    public static void main(String[] args) throws IOException {
        new ClientTest();
    }

    @Override
    public void messageReceived(Client source, Message message) {
        if (message instanceof GameStateMessage) {
            // do something with the message
            GameStateMessage gsMessage = (GameStateMessage) message;
            System.out.println("Client #" + source.getId() + " received: '"
                    + gsMessage.getName() + ", " + gsMessage.getPosition()
                    + ", " + gsMessage.getOrientation() + "'");
        }
    }
}
