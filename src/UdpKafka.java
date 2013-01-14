/*
 *     __  __    __      __ __      ______        
 *    / / / /___/ /___  / //_/___ _/ __/ /______ _
 *   / / / / __  / __ \/ ,< / __ `/ /_/ //_/ __ `/
 *  / /_/ / /_/ / /_/ / /| / /_/ / __/ ,< / /_/ / 
 *  \____/\__,_/ .___/_/ |_\__,_/_/ /_/|_|\__,_/  
 *            /_/                                 
 *
 * @author Ori Livneh <ori@wikimedia.org>
 */
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.Properties;


public class UdpKafka {

    private static final Properties props = new Properties();
    private static String topic;
    private static String udpGroup;
    private static int udpPort;
    private static int udpWorkerId;
    private static int udpWorkers;


    public static void main(String args[]) {

        String configFile = args.length == 0
                ? "udp2kafka.properties"
                : args[0];
        try {
            props.load(new FileInputStream(configFile));
        } catch (IOException e) {
            e.printStackTrace();
        }
        initConfig();

        ProducerConfig config = new ProducerConfig(props);
        Producer<String, String> producer = new Producer<String, String>(config);

        try {
            MulticastSocket sock = new MulticastSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(udpPort));
            sock.joinGroup(InetAddress.getByName(udpGroup));

            byte[] buf = new byte[65536];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            while (true) {
                sock.receive(packet);
                String msg = new String(buf, 0, packet.getLength());
                if (shouldHandle(msg)) {
                    producer.send(new KeyedMessage<String, String>(topic, msg));
                }
                packet.setLength(buf.length);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private static void initConfig() {
        topic = props.getProperty("topic");
        udpGroup = props.getProperty("udplog.group");
        udpPort = Integer.parseInt(props.getProperty("udplog.port"));
        udpWorkerId = Integer.parseInt(props.getProperty("udplog.id"));
        udpWorkers = Integer.parseInt(props.getProperty("udplog.workers"));
    }

    /**
     * Determine if log line should be handled by this worker.
     */
    private static boolean shouldHandle(String msg) {
        long seqId = getSeqId(msg);
        return seqId != -1 && consistentHash(seqId, udpWorkers) == udpWorkerId;
    }

    /**
     * Extract a long sequence ID from udplog log line.
     */
    private static long getSeqId(String msg) {
        int start = msg.indexOf(' ');
        if (start == -1) {
            return -1;
        }

        int end = msg.indexOf(' ', start + 1);
        if (end == -1) {
            return -1;
        }

        try {
            return Long.parseLong(msg.substring(start + 1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Assigns to input a bucket in the range [0, buckets].
     * Adapted from Guava; Copyright (C) 2011 The Guava Authors
     * Licensed under the Apache License, version 2.0.
     */
    private static int consistentHash(long input, int buckets) {
        long h = input;
        int candidate = 0;
        int next;

        while (true) {
            h = 2862933555777941757L * h + 1;
            double inv = 0x1.0p31 / ((int) (h >>> 33) + 1);
            next = (int) ((candidate + 1) * inv);

            if (next >= 0 && next < buckets) {
                candidate = next;
            } else {
                return candidate;
            }
        }
    }
}

