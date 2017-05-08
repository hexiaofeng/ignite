package org.apache.ignite.cache.database;

import java.io.File;
import java.util.concurrent.Callable;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheRebalanceMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.configuration.MemoryPolicyConfiguration;
import org.apache.ignite.configuration.PersistenceConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.cache.database.wal.FileWriteAheadLogManager;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 *
 */
public class IgnitePersistentStoreRemoveDuringRebalancingSelfTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setCacheConfiguration(new CacheConfiguration().setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            .setBackups(1).setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC)
            .setRebalanceMode(CacheRebalanceMode.SYNC));

        MemoryConfiguration dbCfg = new MemoryConfiguration();

        dbCfg.setConcurrencyLevel(Runtime.getRuntime().availableProcessors() * 4);
        dbCfg.setPageSize(1024);

        MemoryPolicyConfiguration memPlcCfg = new MemoryPolicyConfiguration();

        memPlcCfg.setName("dfltMemPlc");
        memPlcCfg.setSize(100 * 1024 * 1024);
        memPlcCfg.setSwapFilePath("db");

        dbCfg.setMemoryPolicies(memPlcCfg);
        dbCfg.setDefaultMemoryPolicyName("dfltMemPlc");

        cfg.setMemoryConfiguration(dbCfg);

        cfg.setPersistenceConfiguration(new PersistenceConfiguration());

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        G.stopAll(true);

        System.setProperty(FileWriteAheadLogManager.IGNITE_PDS_WAL_MODE, "LOG_ONLY");

        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));

        U.delete(new File(U.getIgniteHome(), "db"));
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        G.stopAll(true);

        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));

        U.delete(new File(U.getIgniteHome(), "db"));
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        System.clearProperty(FileWriteAheadLogManager.IGNITE_PDS_WAL_MODE);
    }

    /**
     * @throws Exception if failed.
     */
    public void testRemovesDuringRebalancing() throws Exception {
        IgniteEx ig = startGrid(0);

        try (IgniteDataStreamer<Object, Object> streamer = ig.dataStreamer(null)) {
            streamer.allowOverwrite(true);

            for (int i = 0; i < 100_000; i++)
                streamer.addData(i, i);
        }

        final IgniteCache<Object, Object> cache = ig.cache(null);

        IgniteInternalFuture<Object> fut = GridTestUtils.runAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                return startGrid(1);
            }
        });

        for (int i = 0; i < 100_000; i++)
            cache.remove(i);

        fut.get();

        IgniteEx another = grid(1);

        IgniteCache<Object, Object> cache1 = another.cache(null);

        for (int i = 0; i < 100_000; i++)
            assertNull(cache1.localPeek(i));
    }
}