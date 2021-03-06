package com.vip.saturn.job.internal.sharding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.vip.saturn.job.basic.AbstractSaturnJob;
import com.vip.saturn.job.basic.CrondJob;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.WatchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vip.saturn.job.basic.JobScheduler;
import com.vip.saturn.job.internal.listener.AbstractListenerManager;
import com.vip.saturn.job.threads.SaturnThreadFactory;

/**
 * 分片监听管理器.
 *
 * @author linzhaoming
 *
 */
public class ShardingListenerManager extends AbstractListenerManager {
	static Logger log = LoggerFactory.getLogger(ShardingListenerManager.class);

	private boolean isShutdown;
	
	private CuratorWatcher necessaryWatcher;
	
	private ShardingService shardingService;
	
	private ExecutorService executorService;

	public ShardingListenerManager(final JobScheduler jobScheduler) {
        super(jobScheduler);
		shardingService = jobScheduler.getShardingService();
		if(!isCrondJob(jobScheduler.getCurrentConf().getSaturnJobClass())) { // because crondJob do nothing in onResharding method, no need this watcher
			necessaryWatcher = new NecessaryWatcher();
			executorService = Executors.newSingleThreadExecutor(new SaturnThreadFactory("saturn-sharding-necessary-watcher-" + jobName, false));
		}
    }

	private boolean isCrondJob(Class<?> saturnJobClass) {
		if (saturnJobClass != null) {
			Class<?> superClass = saturnJobClass.getSuperclass();
			String crondJobCanonicalName = CrondJob.class.getCanonicalName();
			String abstractSaturnJobCanonicalName = AbstractSaturnJob.class.getCanonicalName();
			while (superClass != null) {
				String superClassCanonicalName = superClass.getCanonicalName();
				if (superClassCanonicalName.equals(crondJobCanonicalName)) {
					return true;
				}
				if(superClassCanonicalName.equals(abstractSaturnJobCanonicalName)) { // AbstractSaturnJob is CrondJob's parent
					return false;
				}
				superClass = superClass.getSuperclass();
			}
		}
		return false;
	}
	
	@Override
	public void start() {
		if(necessaryWatcher != null) {
			shardingService.registerNecessaryWatcher(necessaryWatcher);
		}
	}

	@Override
	public void shutdown() {
		super.shutdown();
		isShutdown = true;
		if(executorService != null) {
			executorService.shutdownNow();
		}
	}
	
	class NecessaryWatcher implements CuratorWatcher {

		@Override
		public void process(WatchedEvent event) throws Exception {
			switch (event.getType()) {
			case NodeCreated:
			case NodeDataChanged:
				doBusiness(event);
			default:
				shardingService.registerNecessaryWatcher();
			}
		}

		private void doBusiness(final WatchedEvent event) {
			try {
				// cannot block re-registerNecessaryWatcher, so use thread pool to do business
				if(!executorService.isShutdown()) {
					executorService.submit(new Runnable() {
						@Override
						public void run() {
							if (isShutdown) {
								return;
							}		
							if (jobScheduler == null || jobScheduler.getJob() == null) {
								return;
							}
							log.info("[{}] msg={} trigger on-resharding event, type:{}, path:{}", jobName, jobName, event.getType(), event.getPath());
							jobScheduler.getJob().onResharding();
						}
					});
				}
			} catch (Throwable t) {
				log.error(t.getMessage(), t);
			}
		}

	}

}
