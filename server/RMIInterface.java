import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * RMI interface.
 * 
 * @author Zijie Huang
 */
interface RMIInterface extends Remote {
    boolean isMidTierStarted() throws RemoteException;
    void setMidTierStarted(boolean input) throws RemoteException;
    void incrementTotalRequests(int num) throws RemoteException;
    ServerType get(int VMid) throws RemoteException;
    boolean isQueueEmpty() throws RemoteException;
    void enqueue(Cloud.FrontEndOps.Request request) throws RemoteException;
    Cloud.FrontEndOps.Request dequeue() throws RemoteException;
}