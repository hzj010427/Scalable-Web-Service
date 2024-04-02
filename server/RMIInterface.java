import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * RMI (Remote Method Invocation) interface for managing and monitoring a distributed system's state.
 * This interface provides methods to check the status of front-tier and middle-tier servers, manage 
 * request queues, and perform operations related to server management and request processing in a 
 * cloud environment.
 * 
 * @author Zijie Huang
 */
interface RMIInterface extends Remote {

    /**
     * Checks if the middle-tier server has started.
     * 
     * @return {@code true} if the middle-tier server is running, {@code false} otherwise.
     * @throws RemoteException if a remote communication error occurs.
     */
    boolean isMidTierStarted() throws RemoteException;

    /**
     * Sets the status of the middle-tier server.
     * 
     * @param input {@code true} to indicate the middle-tier server is started, {@code false} otherwise.
     * @throws RemoteException if a remote communication error occurs.
     */
    void setMidTierStarted(boolean input) throws RemoteException;

    /**
     * Increments the total number of requests processed by the system.
     * 
     * @param num The number of new requests to add to the total count.
     * @throws RemoteException if a remote communication error occurs.
     */
    void incrementTotalRequests(int num) throws RemoteException;

    /**
     * Retrieves the server type of a specific VM by its ID.
     * 
     * @param VMid The ID of the VM.
     * @return The {@link ServerType} of the specified VM.
     * @throws RemoteException if a remote communication error occurs.
     */
    ServerType get(int VMid) throws RemoteException;

    /**
     * Checks if the request queue is empty.
     * 
     * @return {@code true} if the queue is empty, {@code false} otherwise.
     * @throws RemoteException if a remote communication error occurs.
     */
    boolean isQueueEmpty() throws RemoteException;

    /**
     * Enqueues a new request to be processed.
     * 
     * @param request The request to enqueue.
     * @throws RemoteException if a remote communication error occurs.
     */
    void enqueue(Cloud.FrontEndOps.Request request) throws RemoteException;

    /**
     * Dequeues and returns the next request to be processed.
     * 
     * @return The next {@link Cloud.FrontEndOps.Request} from the queue.
     * @throws RemoteException if the queue is empty or a remote communication error occurs.
     */
    Cloud.FrontEndOps.Request dequeue() throws RemoteException;

    /**
     * Retrieves the current number of middle-tier VMs running.
     * 
     * @return The number of middle-tier VMs.
     * @throws RemoteException if a remote communication error occurs.
     */
    int middleVMNum() throws RemoteException;

    /**
     * Determines whether a specific VM should be terminated based on its ID.
     * 
     * @param VMid The ID of the VM to check.
     * @return {@code true} if the VM should be terminated, {@code false} otherwise.
     * @throws RemoteException if a remote communication error occurs.
     */
    boolean shouldTerminate(int VMid) throws RemoteException;
}
