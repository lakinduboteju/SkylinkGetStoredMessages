package sg.com.temasys.lakinduboteju.skylinkgetstoredmessages;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkerThreadPool {
    private ExecutorService mThreadPool;

    public WorkerThreadPool() {
        final int numberOfCpuCores = Runtime.getRuntime().availableProcessors();
        mThreadPool = Executors.newFixedThreadPool(numberOfCpuCores);
    }

    public void runTask(Runnable r) {
        mThreadPool.execute(r);
    }

    public void shutdown() {
        mThreadPool.shutdownNow();
    }
}
