import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Server class that implements RMIInterface for handling cloud-based requests.
 * This class is responsible for initializing the server based on its role 
 * (Coordinator, Front, or Middle tier), processing incoming requests, and managing 
 * virtual machines (VMs) based on load.
 * 
 * @author Zijie Huang
 */
public class Server extends UnicastRemoteObject implements RMIInterface {
    /* front tier scale out threshold */
    private static final double SCALE_OUT_THRESHOLD_FRONT_LIGHT = 0.8;
    private static final double SCALE_OUT_THRESHOLD_FRONT_MODERATE = 1.2;
    private static final double SCALE_OUT_THRESHOLD_FRONT_HEAVY = 1.5;
    private static final double SCALE_OUT_THRESHOLD_FRONT_VERY_HEAVY = 1.8;
    private static final double SCALE_OUT_THRESHOLD_FRONT_SUPER_HEAVY = 2.1;

    /* middle tier scale out threshold */
    private static final double SCALE_OUT_THRESHOLD_MIDDLE_LIGHT = 0.3;
    private static final double SCALE_OUT_THRESHOLD_MIDDLE_MODERATE = 0.45;
    private static final double SCALE_OUT_THRESHOLD_MIDDLE_HEAVY = 0.7;
    private static final double SCALE_OUT_THRESHOLD_MIDDLE_VERY_HEAVY = 0.9;
    private static final double SCALE_OUT_THRESHOLD_MIDDLE_SUPER_HEAVY = 1.2;
    private static final double SCALE_OUT_THRESHOLD_MIDDLE_EXTREMELY_HEAVY = 1.8;
    private static final double SCALE_OUT_THRESHOLD_MIDDLE_SUPER_EXTREMELY_HEAVY = 2.5;

    /* front tier scale out number */
    private static final double SCALE_OUT_NUM_FRONT_LIGHT = 1;
    private static final double SCALE_OUT_NUM_FRONT_MODERATE = 2;
    private static final double SCALE_OUT_NUM_FRONT_HEAVY = 3;
    private static final double SCALE_OUT_NUM_FRONT_VERY_HEAVY = 5;
    private static final double SCALE_OUT_NUM_FRONT_SUPER_HEAVY = 7;

    /* middle tier scale out number */
    private static final double SCALE_OUT_NUM_MIDDLE_LIGHT = 1;
    private static final double SCALE_OUT_NUM_MIDDLE_MODERATE = 2;
    private static final double SCALE_OUT_NUM_MIDDLE_HEAVY = 3;
    private static final double SCALE_OUT_NUM_MIDDLE_VERY_HEAVY = 4;
    private static final double SCALE_OUT_NUM_MIDDLE_SUPER_HEAVY = 10;
    private static final double SCALE_OUT_NUM_MIDDLE_EXTREMELY_HEAVY = 14;
    private static final double SCALE_OUT_NUM_MIDDLE_SUPER_EXTREMELY_HEAVY = 16;

    /* front tier scale in threshold */
    private static final double SCALE_IN_THRESHOLD_FRONT_LIGHT = 0.3;
    private static final double SCALE_IN_THRESHOLD_FRONT_MODERATE = 0.13;
    private static final double SCALE_IN_THRESHOLD_FRONT_HEAVY = 0.05;

    /* middle tier scale in threshold */
    private static final double SCALE_IN_THRESHOLD_MIDDLE_LIGHT = 0.07;
    private static final double SCALE_IN_THRESHOLD_MIDDLE_MODERATE = 0.04;
    private static final double SCALE_IN_THRESHOLD_MIDDLE_HEAVY = 0.02;

    /* front tier scale in number */
    private static final double SCALE_IN_NUM_FRONT_LIGHT = 1;
    private static final double SCALE_IN_NUM_FRONT_MODERATE = 1;
    private static final double SCALE_IN_NUM_FRONT_HEAVY = 1;

    /* middle tier scale in number */
    private static final double SCALE_IN_NUM_MIDDLE_LIGHT = 1;
    private static final double SCALE_IN_NUM_MIDDLE_MODERATE = 1;
    private static final double SCALE_IN_NUM_MIDDLE_HEAVY = 1;

    /* benchmark parameters */
    private static final double PARSED_REQ_RATE = 3.831; // req/s
    private static final double BROWSE_REQ_RATE = 2.198; // req/s
    private static final double PURCHASE_REQ_RATE = 1.653; // req/s
    private static final double BROWSE_TIME_OUT = 1; // s

    /* other parameters */
    private static final double OVER_HEAD = 0; // s
    private static final long INTERVAL_MANAGE = 5000; // ms
    private static final int MAX_VM_NUM = 24;
    private static final long LOAD_HISTORY_SIZE = INTERVAL_MANAGE / 1000;
    private static final long INTERVAL_LOAD = INTERVAL_MANAGE / LOAD_HISTORY_SIZE;

    /* server parameters */
    private static int port;
    private static String ip;
    private static int VMid;
    private static RMIInterface stub;
    private static ServerLib SL;

    /* data structures and variables for coordinator */
    private static int totalRequests = 0;
    private static int totalParsedRequests = 0;
    private static boolean midTierStarted = false;
    private static int frontVMNum = 0;
    private static int middleVMNum = 0;
    private static List<Double> historyFrontLoad = new LinkedList<>();
    private static List<Double> historyMiddleLoad = new LinkedList<>();
    private static BlockingQueue<Cloud.FrontEndOps.Request> parsedRequestQueue = new LinkedBlockingQueue<>();
    private static ConcurrentHashMap<Integer, ServerType> VMTypeMap = new ConcurrentHashMap<>();
    private static HashSet<Integer> endedVMSet = new HashSet<>();

    /**
     * Constructs a Server instance and exports it on an anonymous port.
     * 
     * @throws RemoteException if failed to export object
     */
    public Server() throws RemoteException {
        super();
    }

    /**
     * The main method for starting the server. It initializes the server based on the command line arguments,
     * which define the cloud IP, cloud port, and VM id. Depending on the VM id, the server assumes the role
     * of coordinator, front tier, or middle tier and performs respective tasks.
     * 
     * @param args Command line arguments containing cloud IP, cloud port, and VM id.
     * @throws Exception If the arguments are invalid or during execution.
     */
    public static void main (String args[]) throws Exception {
        if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
        ip = args[0];
        port = Integer.parseInt(args[1]);
        SL = new ServerLib(ip, port);
        VMid = Integer.parseInt(args[2]);

        if (VMid == 1) { // the first VM is coordinator
            initRMIServer();
            VMTypeMap.put(VMid, ServerType.Coordinator);
            VMTypeMap.put(SL.startVM(), ServerType.MIDDLE); // start two new middle tier VM immediately
            VMTypeMap.put(SL.startVM(), ServerType.MIDDLE);
            frontVMNum++;
            middleVMNum += 2;
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

    /**
     * Processes tasks specific to the Coordinator server, including managing request queues,
     * starting/closing front-tier and middle-tier VMs, and dynamically adjusting the number 
     * of VMs based on load.
     */
    private static void processCoordinator() {
        SL.register_frontend();
        int prevLen = 0;
        int currLen = SL.getQueueLength();
        long startManage = System.currentTimeMillis();
        long startLoad = System.currentTimeMillis();
        int startTotalRequests = totalRequests;
        int prevParsedLen = 0;
        int currParsedLen = parsedRequestQueue.size();
        int startTotalParsedRequests = totalParsedRequests;

        while (true) {
            currLen = handleDrop(currLen);

            int lenDiff = currLen - prevLen;
            prevLen = currLen;
            if (lenDiff > 0) {
                totalRequests += lenDiff;
            }

            int parsedLenDiff = currParsedLen - prevParsedLen;
            prevParsedLen = currParsedLen;
            if (parsedLenDiff > 0) {
                totalParsedRequests += parsedLenDiff;
            }
            
            ServerLib.Handle h = SL.acceptConnection();
            Cloud.FrontEndOps.Request r = SL.parseRequest(h);
            
            if (midTierStarted) {
                parsedRequestQueue.offer(r);
            } else {
                SL.processRequest(r);
            }
            
            long curr = System.currentTimeMillis();
            if (curr - startLoad >= INTERVAL_LOAD) {
                int endTotalRequests = totalRequests;
                int endTotalParsedRequests = totalParsedRequests;
                int totalRequestsDiff = endTotalRequests - startTotalRequests;
                int totalParsedRequestsDiff = endTotalParsedRequests - startTotalParsedRequests;
                double avgReqRate = ((double) totalRequestsDiff / INTERVAL_LOAD) * 1000.0;
                double avgParsedReqRate = ((double) totalParsedRequestsDiff / INTERVAL_LOAD) * 1000.0;
                double frontLoad = avgReqRate / (PARSED_REQ_RATE * frontVMNum);
                double middleLoad = avgReqRate / (BROWSE_REQ_RATE * middleVMNum);

                if (historyFrontLoad.size() == LOAD_HISTORY_SIZE) {
                    historyFrontLoad.remove(0);
                }
                if (historyMiddleLoad.size() == LOAD_HISTORY_SIZE) {
                    historyMiddleLoad.remove(0);
                }
                historyFrontLoad.add(frontLoad);
                historyMiddleLoad.add(middleLoad);

                startTotalRequests = endTotalRequests;
                startLoad = System.currentTimeMillis();
            }

            if (curr - startManage >= INTERVAL_MANAGE) {
                double avgFrontLoad = historyFrontLoad.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
                double avgMiddleLoad = historyMiddleLoad.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
                manageVMs(avgFrontLoad, avgMiddleLoad);
                startManage = System.currentTimeMillis();
            }
        
            currLen = SL.getQueueLength();
            currParsedLen = parsedRequestQueue.size();
        }
    }

    /**
     * Handles the task of dropping requests from the queue based on the estimated wait time
     * and processing capacity. This method aims to maintain response times within acceptable limits.
     * 
     * @param currLen The current length of the request queue.
     * @return The new length of the request queue after dropping tasks if necessary.
     */
    private static int handleDrop(int currLen) {
        try {
            double approxWaitTime 
            = currLen * (1 / PARSED_REQ_RATE) 
            + (parsedRequestQueue.size() / middleVMNum) * (1 / BROWSE_REQ_RATE);

            if (approxWaitTime > BROWSE_TIME_OUT - OVER_HEAD) {
                SL.dropTail();
                currLen--;
            }
        } catch (Exception e) {
            System.err.println("Exception during handleDrop: " + e.toString());
            e.printStackTrace();
            throw new RuntimeException("Error handling drop", e);
        }

        return currLen;
    }

    /**
     * Manages the processing of requests at the front tier. This includes registering the server as a front-end,
     * handling incoming connections, and forwarding requests to the middle tier.
     */
    private static void processFront() {
        try {
            SL.register_frontend();
            int prevLen = 0;
            int currLen = SL.getQueueLength();

            while (true) {
                if (stub.shouldTerminate(VMid)) {
                    SL.shutDown();
                    break;
                }

                // currLen = handleDrop(currLen);

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

    /**
     * Manages the processing of requests at the middle tier. This includes dequeuing requests forwarded by the
     * front tier and processing them. This method also handles the server shutdown when instructed.
     */
    private static void processMiddle() {
        try {
            stub.setMidTierStarted(true);

            while (true) {
                if (stub.shouldTerminate(VMid)) {
                    SL.endVM(VMid);
                    break;
                }

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

    /**
     * Manages the VMs based on the average load of the front and middle tiers. This method calculates the number
     * of VMs to start and stop based on the load thresholds and the current number of VMs.
     * 
     * @param avgFrontLoad The average load of the front tier.
     * @param avgMiddleLoad The average load of the middle tier.
     */
    private static void manageVMs(double avgFrontLoad, double avgMiddleLoad) {
        int currVMNum = VMTypeMap.size();

        System.out.println("--------------------");
        System.out.println("front VM num: " + frontVMNum);
        System.out.println("middle VM num: " + middleVMNum);
        System.out.println("avgFrontLoad: " + avgFrontLoad);
        System.out.println("avgMiddleLoad: " + avgMiddleLoad);

        int frontVMToStart = calculateFrontVMToStart(avgFrontLoad, currVMNum);
        int middleVMToStart = calculateMiddleVMToStart(avgMiddleLoad, currVMNum);
        int frontVMToStop = calculateFrontVMToStop(avgFrontLoad);
        int middleVMToStop = calculateMiddleVMToStop(avgMiddleLoad);

        System.out.println("frontVMToStart: " + frontVMToStart);
        System.out.println("middleVMToStart: " + middleVMToStart);
        System.out.println("frontVMToStop: " + frontVMToStop);
        System.out.println("middleVMToStop: " + middleVMToStop);
        System.out.println("--------------------");
        System.out.println();

        startVMs(frontVMToStart, ServerType.FRONT);
        startVMs(middleVMToStart, ServerType.MIDDLE);
        stopVMs(frontVMToStop, ServerType.FRONT);
        stopVMs(middleVMToStop, ServerType.MIDDLE);
    }

    /**
     * Calculates the number of front-tier VMs to start based on the average load and the current number of VMs.
     * 
     * @param frontLoad The average load of the front tier.
     * @param currVMNum The current number of VMs.
     * @return The number of front-tier VMs to start.
     */
    private static int calculateFrontVMToStart(double frontLoad, int currVMNum) {
        if (frontLoad >= SCALE_OUT_THRESHOLD_FRONT_LIGHT && frontLoad < SCALE_OUT_THRESHOLD_FRONT_MODERATE) {
            return Math.min((int) SCALE_OUT_NUM_FRONT_LIGHT, MAX_VM_NUM - currVMNum);
        } else if (frontLoad >= SCALE_OUT_THRESHOLD_FRONT_MODERATE && frontLoad < SCALE_OUT_THRESHOLD_FRONT_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_FRONT_MODERATE, MAX_VM_NUM - currVMNum);
        } else if (frontLoad >= SCALE_OUT_THRESHOLD_FRONT_HEAVY && frontLoad < SCALE_OUT_THRESHOLD_FRONT_VERY_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_FRONT_HEAVY, MAX_VM_NUM - currVMNum);
        } else if (frontLoad >= SCALE_OUT_THRESHOLD_FRONT_VERY_HEAVY && frontLoad < SCALE_OUT_THRESHOLD_FRONT_SUPER_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_FRONT_VERY_HEAVY, MAX_VM_NUM - currVMNum);
        } else if (frontLoad >= SCALE_OUT_THRESHOLD_FRONT_SUPER_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_FRONT_SUPER_HEAVY, MAX_VM_NUM - currVMNum);
        }
        return 0;
    }

    /**
     * Calculates the number of middle-tier VMs to start based on the average load and the current number of VMs.
     * 
     * @param middleLoad The average load of the middle tier.
     * @param currVMNum The current number of VMs.
     * @return The number of middle-tier VMs to start.
     */
    private static int calculateMiddleVMToStart(double middleLoad, int currVMNum) {
        if (middleLoad >= SCALE_OUT_THRESHOLD_MIDDLE_LIGHT && middleLoad < SCALE_OUT_THRESHOLD_MIDDLE_MODERATE) {
            return Math.min((int) SCALE_OUT_NUM_MIDDLE_LIGHT, MAX_VM_NUM - currVMNum);
        } else if (middleLoad >= SCALE_OUT_THRESHOLD_MIDDLE_MODERATE && middleLoad < SCALE_OUT_THRESHOLD_MIDDLE_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_MIDDLE_MODERATE, MAX_VM_NUM - currVMNum);
        } else if (middleLoad >= SCALE_OUT_THRESHOLD_MIDDLE_HEAVY && middleLoad < SCALE_OUT_THRESHOLD_MIDDLE_VERY_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_MIDDLE_HEAVY, MAX_VM_NUM - currVMNum);
        } else if (middleLoad >= SCALE_OUT_THRESHOLD_MIDDLE_VERY_HEAVY && middleLoad < SCALE_OUT_THRESHOLD_MIDDLE_SUPER_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_MIDDLE_VERY_HEAVY, MAX_VM_NUM - currVMNum);
        } else if (middleLoad >= SCALE_OUT_THRESHOLD_MIDDLE_SUPER_HEAVY && middleLoad < SCALE_OUT_THRESHOLD_MIDDLE_EXTREMELY_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_MIDDLE_SUPER_HEAVY, MAX_VM_NUM - currVMNum);
        } else if (middleLoad >= SCALE_OUT_THRESHOLD_MIDDLE_EXTREMELY_HEAVY && middleLoad < SCALE_OUT_THRESHOLD_MIDDLE_SUPER_EXTREMELY_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_MIDDLE_EXTREMELY_HEAVY, MAX_VM_NUM - currVMNum);
        } else if (middleLoad >= SCALE_OUT_THRESHOLD_MIDDLE_SUPER_EXTREMELY_HEAVY) {
            return Math.min((int) SCALE_OUT_NUM_MIDDLE_SUPER_EXTREMELY_HEAVY, MAX_VM_NUM - currVMNum);
        }
        return 0;
    }

    /**
     * Calculates the number of front-tier VMs to stop based on the average load.
     * 
     * @param frontLoad The average load of the front tier.
     * @return The number of front-tier VMs to stop.
     */
    private static int calculateFrontVMToStop(double frontLoad) {
        if (frontLoad <= SCALE_IN_THRESHOLD_FRONT_LIGHT && frontLoad > SCALE_IN_THRESHOLD_FRONT_MODERATE) {
            return Math.min((int) SCALE_IN_NUM_FRONT_LIGHT, frontVMNum - 1);
        } else if (frontLoad <= SCALE_IN_THRESHOLD_FRONT_MODERATE && frontLoad > SCALE_IN_THRESHOLD_FRONT_HEAVY) {
            return Math.min((int) SCALE_IN_NUM_FRONT_MODERATE, frontVMNum - 1);
        } else if (frontLoad <= SCALE_IN_THRESHOLD_FRONT_HEAVY) {
            return Math.min((int) SCALE_IN_NUM_FRONT_HEAVY, frontVMNum - 1);
        }
        return 0;
    }

    /**
     * Calculates the number of middle-tier VMs to stop based on the average load.
     * 
     * @param middleLoad The average load of the middle tier.
     * @return The number of middle-tier VMs to stop.
     */
    private static int calculateMiddleVMToStop(double middleLoad) {
        if (middleLoad <= SCALE_IN_THRESHOLD_MIDDLE_LIGHT && middleLoad > SCALE_IN_THRESHOLD_MIDDLE_MODERATE) {
            return Math.min((int) SCALE_IN_NUM_MIDDLE_LIGHT, middleVMNum - 1);
        } else if (middleLoad <= SCALE_IN_THRESHOLD_MIDDLE_MODERATE && middleLoad > SCALE_IN_THRESHOLD_MIDDLE_HEAVY) {
            return Math.min((int) SCALE_IN_NUM_MIDDLE_MODERATE, middleVMNum - 1);
        } else if (middleLoad <= SCALE_IN_THRESHOLD_MIDDLE_HEAVY) {
            return Math.min((int) SCALE_IN_NUM_MIDDLE_HEAVY, middleVMNum - 1);
        }
        return 0;
    }

    /**
     * Starts a specified number of virtual machines (VMs) of a given type. 
     * It registers each new VM in a map with its ID and type, and increments the count of VMs accordingly.
     *
     * @param VMToStart The number of VMs to start.
     * @param type The type of the VMs to start, either FRONT or MIDDLE.
     */
    private static void startVMs(int VMToStart, ServerType type) {
        for (int i = 0; i < VMToStart; i++) {
            int id = SL.startVM();
            VMTypeMap.put(id, type);
            System.out.println("(START " + type.toString() + ")Starting " + type.toString() + " tier VM: " + id);
            if (type == ServerType.FRONT) {
                frontVMNum++;
            } else if (type == ServerType.MIDDLE) {
                middleVMNum++;
            }
        }
    }

    /**
     * Stops a specified number of virtual machines (VMs) of a given type.
     * It prioritizes stopping VMs that are still booting before stopping those that are running.
     * Each stopped VM is removed from the map and its count is decremented.
     *
     * @param VMToStop The number of VMs to stop.
     * @param type The type of the VMs to stop, either FRONT or MIDDLE.
     */
    private static void stopVMs(int VMToStop, ServerType type) {
        for (int i = 0; i < VMToStop; i++) {
            int VMidToStop = VMTypeMap.entrySet().stream()
                    .filter(entry -> entry.getValue() == type)
                    .sorted((e1, e2) -> {
                        // stop the VM that is booting first
                        Cloud.CloudOps.VMStatus status1 = SL.getStatusVM(e1.getKey());
                        Cloud.CloudOps.VMStatus status2 = SL.getStatusVM(e2.getKey());
                        if (status1 == Cloud.CloudOps.VMStatus.Booting && status2 != Cloud.CloudOps.VMStatus.Booting) {
                            return -1;
                        } else if (status1 != Cloud.CloudOps.VMStatus.Booting && status2 == Cloud.CloudOps.VMStatus.Booting) {
                            return 1;
                        }
                        return 0;
                    })
                    .findFirst()
                    .get()
                    .getKey();
            System.out.println("(STOP " + type.toString() + ")Stopping " + type.toString() + " tier VM: " + VMidToStop);
            endedVMSet.add(VMidToStop);
            VMTypeMap.remove(VMidToStop);
            if (type == ServerType.FRONT) {
                frontVMNum--;
            } else if (type == ServerType.MIDDLE) {
                middleVMNum--;
            }
        }
    }

    /**
     * Initializes the RMI connection with the server. 
     * This method locates the RMI registry at the specified IP and port, 
     * and looks up the RMI interface to establish a connection.
     *
     * @throws RuntimeException if there is a RemoteException or NotBoundException during RMI initialization.
     */
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

    /**
     * Initializes the RMI server and binds the Server instance to the RMI registry.
     * It creates the RMI registry on a specified port and binds the current Server instance with a predefined name.
     *
     * @throws RuntimeException if there is any exception during the RMI server initialization process.
     */
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

    @Override
    public int middleVMNum() throws RemoteException {
        return middleVMNum;
    }

    @Override
    public boolean shouldTerminate(int VMid) throws RemoteException {
        return endedVMSet.contains(VMid);
    }
}
