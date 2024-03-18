import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Server {
    private static int currVMs = 0;
    private static List<Integer> VMs = new LinkedList<>();
    private static ServerLib SL;

    private static final int MORNING_START = 0;
    private static final int MORNING_END = 7;
    private static final int EVENING_START = 19;
    private static final int DAY_END = 24;

    private static final int VM_COUNT_IDLE = 2;
    private static final int VM_COUNT_NORMAL = 3;
    private static final int VM_COUNT_BUSY = 5;

	public static void main (String args[]) throws Exception {
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		SL = new ServerLib(args[0], Integer.parseInt(args[1]));
		int myVMid = Integer.parseInt(args[2]);

        if (myVMid == 1) { // the first VM will manage the rest
            manageVMs();
        }
        
		SL.register_frontend();
        currVMs++;
        VMs.add(myVMid);
		
		// main loop
		while (true) {
			ServerLib.Handle h = SL.acceptConnection();
			Cloud.FrontEndOps.Request r = SL.parseRequest(h);
			SL.processRequest(r);
		}
	}

    private static void manageVMs() {
        float time = SL.getTime();
        int targetVMs = getTargetVMCnt(time);

        while (currVMs < targetVMs) {
            int newVM = SL.startVM();
            VMs.add(newVM);
            currVMs++;
        }
    }

    private static int getTargetVMCnt(float time) {
        if (time >= MORNING_START && time < MORNING_END) {
            return VM_COUNT_IDLE;
        } else if (time >= MORNING_END && time < EVENING_START) {
            return VM_COUNT_NORMAL;
        } else if (time >= EVENING_START && time <= DAY_END) {
            return VM_COUNT_BUSY;
        } else {
            return -1; // error
        }
    }
}
