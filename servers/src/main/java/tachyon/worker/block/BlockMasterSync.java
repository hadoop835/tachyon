package tachyon.worker.block;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tachyon.Constants;
import tachyon.conf.TachyonConf;
import tachyon.master.MasterClient;
import tachyon.thrift.BlockInfoException;
import tachyon.thrift.Command;
import tachyon.thrift.NetAddress;
import tachyon.util.CommonUtils;
import tachyon.util.NetworkUtils;
import tachyon.util.ThreadFactoryUtils;

/**
 * Task that carries out the necessary block worker to master communications, including register and
 * heartbeat. This class manages its own {@link tachyon.master.MasterClient}.
 *
 * When running, this task first requests a block report from the
 * {@link tachyon.worker.block.BlockDataManager}, then sends it to the master. The master may
 * respond to the heartbeat with a command which will be executed. After which, the task will wait
 * for the elapsed time since its last heartbeat has reached the heartbeat interval. Then the cycle
 * will continue.
 *
 * If the task fails to heartbeat to the worker, it will destroy its old master client and recreate
 * it before retrying.
 */
public class BlockMasterSync implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** Block data manager responsible for interacting with Tachyon and UFS storage */
  private final BlockDataManager mBlockDataManager;
  /** The executor service for the master client thread */
  private final ExecutorService mMasterClientExecutorService;
  /** The net address of the worker */
  private final NetAddress mWorkerAddress;
  /** The configuration values */
  private final TachyonConf mTachyonConf;
  /** Milliseconds between each heartbeat */
  private final int mHeartbeatIntervalMs;
  /** Milliseconds between heartbeats before a timeout */
  private final int mHeartbeatTimeoutMs;

  /** Client for all master communication */
  private MasterClient mMasterClient;
  /** Flag to indicate if the sync should continue */
  private boolean mRunning;
  /** The id of the worker */
  private long mWorkerId;

  BlockMasterSync(BlockDataManager blockDataManager, TachyonConf tachyonConf,
      NetAddress workerAddress) {
    mBlockDataManager = blockDataManager;
    mWorkerAddress = workerAddress;
    mTachyonConf = tachyonConf;
    mMasterClientExecutorService =
        Executors.newFixedThreadPool(1, ThreadFactoryUtils.daemon("worker-client-heartbeat-%d"));
    mMasterClient =
        new MasterClient(getMasterAddress(), mMasterClientExecutorService, mTachyonConf);
    mHeartbeatIntervalMs =
        mTachyonConf.getInt(Constants.WORKER_TO_MASTER_HEARTBEAT_INTERVAL_MS, Constants.SECOND_MS);
    mHeartbeatTimeoutMs =
        mTachyonConf.getInt(Constants.WORKER_HEARTBEAT_TIMEOUT_MS, 10 * Constants.SECOND_MS);

    mRunning = true;
    mWorkerId = 0;
  }

  /**
   * Gets the Tachyon master address from the configuration
   * @return the InetSocketAddress of the master
   */
  private InetSocketAddress getMasterAddress() {
    String masterHostname =
        mTachyonConf.get(Constants.MASTER_HOSTNAME, NetworkUtils.getLocalHostName(mTachyonConf));
    int masterPort = mTachyonConf.getInt(Constants.MASTER_PORT, Constants.DEFAULT_MASTER_PORT);
    return new InetSocketAddress(masterHostname, masterPort);
  }

  /**
   * Handles a master command. The command is one of Unknown, Nothing, Register, Free, or Delete.
   * This call will block until the command is complete.
   * @param cmd the command to execute.
   */
  private void handleMasterCommand(Command cmd) {
    if (cmd != null) {
      switch (cmd.mCommandType) {
        case Unknown:
          LOG.error("Unknown command: " + cmd);
          break;
        case Nothing:
          LOG.debug("Nothing command: {}", cmd);
          break;
        case Register:
          LOG.info("Register command: " + cmd);
          try {
            registerWithMaster();
          } catch (Exception e) {
            LOG.error("Failed to register with master.", e);
          }
          break;
        case Free:
          LOG.info("Free command: " + cmd);
          for (long block : cmd.mData) {
            try {
              // TODO: Define constants for system user.
              mBlockDataManager.freeBlock(-1, block);
            } catch (IOException ioe) {
              LOG.error("Failed to free blocks: " + cmd.mData, ioe);
            }
          }
          break;
        case Delete:
          LOG.info("Delete command: " + cmd);
          break;
        default:
          throw new RuntimeException("Un-recognized command from master " + cmd.toString());
      }
    }
  }

  /**
   * Registers with the Tachyon master. This should be called before the continuous heartbeat
   * thread begins. The workerId will be set after this method is successful.
   * @throws IOException if the registration fails
   */
  public void registerWithMaster() throws IOException {
    BlockStoreMeta storeMeta = mBlockDataManager.getStoreMeta();
    try {
      mWorkerId =
          mMasterClient.worker_register(mWorkerAddress, storeMeta.getCapacityBytesOnTiers(),
              storeMeta.getUsedBytesOnTiers(), storeMeta.getBlockList());
    } catch (BlockInfoException bie) {
      LOG.error("Failed to register with master.", bie);
      throw new IOException(bie);
    }
  }

  /**
   * Closes and creates a new master client, in case the master changes.
   */
  private void resetMasterClient() {
    mMasterClient.close();
    mMasterClient =
        new MasterClient(getMasterAddress(), mMasterClientExecutorService, mTachyonConf);
  }

  /**
   * Gets the worker id, 0 if registerWithMaster has not been called successfully.
   * @return the worker id
   */
  public long getWorkerId() {
    return mWorkerId;
  }

  /**
   * Main loop for the syncer, continuously heartbeats to the master node about the change in the
   * worker's managed space.
   */
  @Override
  public void run() {
    long lastHeartbeatMs = System.currentTimeMillis();
    Command cmd = null;
    while (mRunning) {
      long diff = System.currentTimeMillis() - lastHeartbeatMs;
      if (diff < mHeartbeatIntervalMs) {
        LOG.debug("Heartbeat process takes {} ms.", diff);
        CommonUtils.sleepMs(LOG, mHeartbeatIntervalMs - diff);
      } else {
        LOG.warn("Heartbeat took " + diff + " ms, expected " + mHeartbeatIntervalMs + " ms.");
      }
      try {
        BlockHeartbeatReport blockReport = mBlockDataManager.getReport();
        BlockStoreMeta storeMeta = mBlockDataManager.getStoreMeta();
        cmd =
            mMasterClient.worker_heartbeat(mWorkerId, storeMeta.getUsedBytesOnTiers(),
                blockReport.getRemovedBlocks(), blockReport.getAddedBlocks());
        lastHeartbeatMs = System.currentTimeMillis();
      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
        resetMasterClient();
        CommonUtils.sleepMs(LOG, Constants.SECOND_MS);
        cmd = null;
        diff = System.currentTimeMillis() - lastHeartbeatMs;
        if (diff >= mHeartbeatTimeoutMs) {
          throw new RuntimeException("Heartbeat timeout " + diff + "ms");
        }
      }
      // TODO: Is there a way to make this async? Could take much longer than heartbeat timeout.
      handleMasterCommand(cmd);
      // TODO: This should go in its own thread
      mBlockDataManager.cleanupUsers();
    }
  }

  public void stop() {
    mRunning = false;
    mMasterClient.close();
    mMasterClientExecutorService.shutdown();
  }
}
