import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Server class.
 * 
 * @author Zijie Huang
 */
public class Server extends UnicastRemoteObject implements RMIInterface {
    private static final double SCALE_UP_THRESHOLD_FRONT = 1.3;
    private static final double SCALE_UP_THRESHOLD_MIDDLE = 0.27;
    // private static final double SCALE_UP_THRESHOLD = 0.4;
    // private static final int DROP_REQ_THRESHOLD = 4;
    private static final double PARSED_REQ_RATE = 3.831; // req/s
    private static final double BROWSE_REQ_RATE = 2.198; // req/s
    private static final double PURCHASE_REQ_RATE = 1.653; // req/s
    private static final double BROWSE_REQ_WEIGHT = 1;
    private static final double PURCHASE_REQ_WEIGHT = 0;
    private static final long INTERVAL = 3000; // ms
    private static final int MAX_VM_NUM = 24;

    private static int port;
    private static String ip;
    private static int VMid;
    private static RMIInterface stub;
    private static ServerLib SL;

    private static int totalRequests = 0;
    private static boolean midTierStarted = false;
    private static int frontVMNum = 0;
    private static int middleVMNum = 0;
    private static BlockingQueue<Cloud.FrontEndOps.Request> parsedRequestQueue = new LinkedBlockingQueue<>();
    private static ConcurrentHashMap<Integer, ServerType> VMTypeMap = new ConcurrentHashMap<>();

    public Server() throws RemoteException {
        super();
    }

    public static void main (String args[]) throws Exception {
        if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
        ip = args[0];
        port = Integer.parseInt(args[1]);
        SL = new ServerLib(ip, port);
        VMid = Integer.parseInt(args[2]);

        if (VMid == 1) { // the first VM is coordinator
            initRMIServer();
            VMTypeMap.put(VMid, ServerType.Coordinator);
            VMTypeMap.put(SL.startVM(), ServerType.MIDDLE); // start a new middle tier VM immediately
            frontVMNum++;
            middleVMNum++;
        }
            
        initRMIConnection();

        if (stub.get(VMid) == ServerType.Coordinator) {
            processCoordinator();
        } 
        if (stub.get(VMid) == ServerType.FRONT) {
            processFront();
        } 
        if (stub.get(VMid) == ServerType.MIDDLE) {
            processMiddle();
        }
    }

    private static void processCoordinator() {
        SL.register_frontend();
        int prevLen = 0;
        int currLen = SL.getQueueLength();
        long start = System.currentTimeMillis();
        int startTotalRequests = totalRequests;

        // for (int i = 0; i < 0; i++) {
        //     VMTypeMap.put(SL.startVM(), ServerType.FRONT);
        // }

        // for (int i = 0; i < 4; i++) {
        //     VMTypeMap.put(SL.startVM(), ServerType.MIDDLE);
        // }

        while (true) {
            // while (currLen > DROP_REQ_THRESHOLD) {
            //     SL.dropTail();
            //     currLen--;
            // }
            // System.out.println("currLen: " + currLen);
            // System.out.println("prevLen: " + prevLen);
            int lenDiff = currLen - prevLen;
            prevLen = currLen;
            if (lenDiff > 0) {
                totalRequests += lenDiff;
            }
            // System.out.println("totalRequests: " + totalRequests);

            ServerLib.Handle h = SL.acceptConnection();
            Cloud.FrontEndOps.Request r = SL.parseRequest(h);
            
            if (midTierStarted) {
                parsedRequestQueue.offer(r);
            } else {
                SL.processRequest(r);
            }
            
            long curr = System.currentTimeMillis();
            if (curr - start >= INTERVAL) {
                int endTotalRequests = totalRequests;

                // long timeDiff = curr - start;
                // System.out.println("Time diff (ms): " + timeDiff);
                int totalRequestsDiff = endTotalRequests - startTotalRequests;
                // System.out.println("Total requests diff: " + totalRequestsDiff);
                double avgReqRate = ((double) totalRequestsDiff / INTERVAL) * 1000.0;
                
                manageVMs(avgReqRate);

                start = System.currentTimeMillis();
                startTotalRequests = endTotalRequests;
            }
            
            currLen = SL.getQueueLength();
        }
    }

    private static void processFront() {
        try {
            System.out.println("Front tier: " + VMid + " started.");
            SL.register_frontend();
            int prevLen = 0;
            int currLen = SL.getQueueLength();

            while (true) {
                // while (currLen > DROP_REQ_THRESHOLD) {
                //     SL.dropTail();
                //     currLen--;
                // }

                int lenDiff = currLen - prevLen;
                prevLen = currLen;
                if (lenDiff > 0) {
                    stub.incrementTotalRequests(lenDiff);
                }

                ServerLib.Handle h = SL.acceptConnection();
                Cloud.FrontEndOps.Request r = SL.parseRequest(h);
                stub.enqueue(r);

                currLen = SL.getQueueLength();
            }
        } catch (Exception e) {
            System.err.println("Exception during processFront: " + e.toString());
            e.printStackTrace();
            throw new RuntimeException("Error processing front tier", e);
        }
    }

    private static void processMiddle() {
        try {
            System.out.println("Middle tier: " + VMid + " started.");
            stub.setMidTierStarted(true);

            while (true) {
                if (!stub.isQueueEmpty()) {
                    Cloud.FrontEndOps.Request r = stub.dequeue();
                    SL.processRequest(r);
                }
            }
        } catch (Exception e) {
            System.err.println("Exception during processMiddle: " + e.toString());
            e.printStackTrace();
            throw new RuntimeException("Error processing middle tier", e);
        }
    }

    private static void manageVMs(double avgReqRate) {
        // System.out.println("avgReqRate: " + avgReqRate);
        double frontLoad = 
            avgReqRate / (PARSED_REQ_RATE * frontVMNum);
            // avgReqRate / PARSED_REQ_RATE;
        double middleLoad = 
            avgReqRate / ((BROWSE_REQ_RATE * BROWSE_REQ_WEIGHT + PURCHASE_REQ_RATE * PURCHASE_REQ_WEIGHT) * middleVMNum);
            // avgReqRate / (PURCHASE_REQ_RATE * middleVMNum);
            // avgReqRate / PURCHASE_REQ_RATE;
            // avgReqRate / (BROWSE_REQ_RATE * BROWSE_REQ_WEIGHT + PURCHASE_REQ_RATE * PURCHASE_REQ_WEIGHT);
        int currVMNum = VMTypeMap.size();
        int frontVMToStart = 0;
        int middleVMToStart = 0;

        // System.out.println("Front load: " + frontLoad);
        // System.out.println("Middle load: " + middleLoad);

        // scale up
        if (frontLoad >= SCALE_UP_THRESHOLD_FRONT) {
            frontVMToStart = 
                Math.min((int) Math.floor(frontLoad), MAX_VM_NUM - currVMNum);
        }

        for (int i = 0; i < frontVMToStart; i++) {
            VMTypeMap.put(SL.startVM(), ServerType.FRONT);
            frontVMNum++;
        }

        if (middleLoad >= SCALE_UP_THRESHOLD_MIDDLE) {
            middleVMToStart = 
                Math.min((int) Math.ceil(middleLoad), MAX_VM_NUM - currVMNum);
        }

        for (int i = 0; i < middleVMToStart; i++) {
            VMTypeMap.put(SL.startVM(), ServerType.MIDDLE);
            middleVMNum++;
        }
    }

    private static void initRMIConnection() {
        try {
            Registry registry = LocateRegistry.getRegistry(ip, port + 1);
            stub = (RMIInterface) registry.lookup("RMIInterface");
            System.err.println("RMI connection initialized successfully.");
        } catch (RemoteException e) {
            System.err.println("RemoteException during RMI initialization: " + e.toString());
            e.printStackTrace();
            throw new RuntimeException("Error initializing RMI connection", e);
        } catch (NotBoundException e) {
            System.err.println("NotBoundException during RMI initialization: " + e.toString());
            e.printStackTrace();
            throw new RuntimeException("RMI service not bound", e);
        }
    }

    private static void initRMIServer() {
        try {
            Server server = new Server();
            Registry registry = LocateRegistry.createRegistry(port + 1);
            registry.bind("RMIInterface", server);
            System.err.println("RMI server ready.");
        } catch (RemoteException e) {
            System.err.println("RemoteException during RMI server initialization: " + e.toString());
            e.printStackTrace();
            throw new RuntimeException("Error initializing RMI server", e);
        } catch (Exception e) {
            System.err.println("Exception during RMI server initialization: " + e.toString());
            e.printStackTrace();
            throw new RuntimeException("Error initializing RMI server", e);
        }
    }

    @Override
    public void enqueue(Cloud.FrontEndOps.Request request) throws RemoteException {
        parsedRequestQueue.offer(request);
    }

    @Override
    public Cloud.FrontEndOps.Request dequeue() throws RemoteException {
        return parsedRequestQueue.poll();
    }

    @Override
    public boolean isQueueEmpty() throws RemoteException {
        return parsedRequestQueue.isEmpty();
    }

    @Override
    public ServerType get(int VMid) throws RemoteException {
        return VMTypeMap.get(VMid);
    }

    @Override
    public boolean isMidTierStarted() throws RemoteException {
        return midTierStarted;
    }

    @Override
    public void setMidTierStarted(boolean input) throws RemoteException {
        midTierStarted = input;
    }

    @Override
    public void incrementTotalRequests(int num) throws RemoteException {
        totalRequests += num;
    }
}