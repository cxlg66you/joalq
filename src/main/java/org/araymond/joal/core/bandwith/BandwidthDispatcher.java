package org.araymond.joal.core.bandwith;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.araymond.joal.core.bandwith.weight.PeersAwareWeightCalculator;
import org.araymond.joal.core.bandwith.weight.WeightHolder;
import org.araymond.joal.core.torrent.torrent.InfoHash;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.slf4j.LoggerFactory.getLogger;

public class BandwidthDispatcher implements BandwidthDispatcherFacade, Runnable {
    private static final Logger logger = getLogger(BandwidthDispatcher.class);
    private final ReentrantReadWriteLock lock;
    private final WeightHolder<InfoHash> weightHolder;
    private final RandomSpeedProvider randomSpeedProvider;
    private final Map<InfoHash, TorrentSeedStats> torrentsSeedStats;
    private final Map<InfoHash, Speed> speedMap;
    private SpeedChangedListener speedChangedListener;
    private final int threadPauseInterval;
    private int threadLoopCounter = 0;
    private volatile boolean stop = false;
    private Thread thread;

    public BandwidthDispatcher(final int threadPauseInterval, final RandomSpeedProvider randomSpeedProvider) {
        this.threadPauseInterval = threadPauseInterval;
        this.torrentsSeedStats = new HashMap<>();
        this.speedMap = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();


        this.weightHolder = new WeightHolder<>(new PeersAwareWeightCalculator());
        this.randomSpeedProvider = randomSpeedProvider;
    }

    public void setSpeedListener(final SpeedChangedListener speedListener) {
        this.speedChangedListener = speedListener;
    }

    /*
     * This method does not benefit from the lock, because the value will never be accessed in a ambiguous way.
     * And even if it happens, we returns 0 by default.
     */
    public TorrentSeedStats getSeedStatForTorrent(final InfoHash infoHash) {
        final TorrentSeedStats torrentSeedStats = this.torrentsSeedStats.get(infoHash);
        return torrentSeedStats == null ? new TorrentSeedStats() : torrentSeedStats;
    }

    public Map<InfoHash, Speed> getSpeedMap() {
        try {
            this.lock.readLock().lock();
            return Maps.newHashMap(speedMap);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void start() {
        this.stop = false;
        this.thread = new Thread(this);
        this.thread.setName("bandwidth-dispatcher");
        this.thread.start();
    }

    public void stop() {
        this.stop = true;
        this.thread.interrupt();
        try {
            this.thread.join();
        } catch (final InterruptedException ignored) {
        }
    }

    @Override
    public void run() {
        try {
            while (!this.stop) {
                Thread.sleep(this.threadPauseInterval);
                ++this.threadLoopCounter;
                // refresh bandwidth every 1200000 milliseconds (20 minutes)
                if (this.threadLoopCounter == 1200000 / this.threadPauseInterval) {
                    this.refreshCurrentBandwidth();
                    this.threadLoopCounter = 0;
                }

                // This method as to run as fast as possible to avoid blocking other ones. Because we wan't this loop
                //  to be scheduled as precise as we can. Locking to much will delay the Thread.sleep and cause stats
                //  to be undervalued
                this.lock.readLock().lock();
                final Set<Map.Entry<InfoHash, TorrentSeedStats>> entrySet = Sets.newHashSet(this.torrentsSeedStats.entrySet());
                this.lock.readLock().unlock();

                for (final Map.Entry<InfoHash, TorrentSeedStats> entry : entrySet) {
                    final Speed speed = this.speedMap.get(entry.getKey());
                    final long speedInBytesPerSecond = speed == null ? 0: speed.getBytesPerSeconds(); // avoid Map#getOrDefault as it will trigger a lot of Speed object instantiation for nothing.
                    // Divide by 1000 because of the thread pause interval being in milliseconds
                    // The multiplication HAS to be done before the division, otherwise we're going to have trailing zeroes
                    entry.getValue().addUploaded((speedInBytesPerSecond * this.threadPauseInterval) / 1000);
                }
            }
        } catch (final InterruptedException ignore) {
        }
    }

    public void updateTorrentPeers(final InfoHash infoHash, final int seeders, final int leechers) {
        if (logger.isDebugEnabled()) {
            logger.debug("Updating Peers stats for {}", infoHash.humanReadableValue());
        }
        this.lock.writeLock().lock();
        try {
            this.weightHolder.addOrUpdate(infoHash, new Peers(seeders, leechers));
            this.recomputeSpeeds();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void registerTorrent(final InfoHash infoHash) {
        if (logger.isDebugEnabled()) {
            logger.debug("{} has been added to bandwidth dispatcher.", infoHash.humanReadableValue());
        }
        this.lock.writeLock().lock();
        try {
            this.torrentsSeedStats.put(infoHash, new TorrentSeedStats());
            this.speedMap.put(infoHash, new Speed(0));
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void unregisterTorrent(final InfoHash infoHash) {
        if (logger.isDebugEnabled()) {
            logger.debug("{} has been removed from bandwidth dispatcher.", infoHash.humanReadableValue());
        }
        this.lock.writeLock().lock();
        try {
            this.weightHolder.remove(infoHash);
            this.torrentsSeedStats.remove(infoHash);
            this.speedMap.remove(infoHash);
            this.recomputeSpeeds();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @VisibleForTesting
    void refreshCurrentBandwidth() {
        if (logger.isDebugEnabled()) {
            logger.debug("Refreshing global bandwidth");
        }
        this.lock.writeLock().lock();
        try {
            this.randomSpeedProvider.refresh();
            this.recomputeSpeeds();
            if (logger.isDebugEnabled()) {
                logger.debug("Global bandwidtwh refreshed, new value is {}", FileUtils.byteCountToDisplaySize(this.randomSpeedProvider.getInBytesPerSeconds()));
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @VisibleForTesting
    void recomputeSpeeds() {
        if (logger.isDebugEnabled()) {
            logger.debug("Refreshing all torrents speeds");
        }
        for (final InfoHash infohash : this.torrentsSeedStats.keySet()) {
            final double percentOfSpeedAssigned = this.weightHolder.getTotalWeight() == 0.0
                    ? 0.0
                    : this.weightHolder.getWeightFor(infohash) / this.weightHolder.getTotalWeight();

            this.speedMap.compute(infohash, (hash, speed) -> {
                if (speed == null) {
                    return new Speed(0);
                }
                speed.setBytesPerSeconds((long) (this.randomSpeedProvider.getInBytesPerSeconds() * percentOfSpeedAssigned));
                return speed;
            });
        }
        if (speedChangedListener != null) {
            this.speedChangedListener.speedsHasChanged(Maps.newHashMap(this.speedMap));
        }
        try {
            if (logger.isDebugEnabled()) {
                final StringBuilder sb = new StringBuilder("All torrents speeds has been refreshed:\n");
                final double totalWeight = this.weightHolder.getTotalWeight();
                this.speedMap.forEach((infoHash, speed) -> {
                    final String humanReadableSpeed = FileUtils.byteCountToDisplaySize(speed.getBytesPerSeconds());
                    final double torrentWeight = this.weightHolder.getWeightFor(infoHash);
                    final double weightInPercent = torrentWeight > 0.0
                            ? totalWeight / torrentWeight * 100
                            : 0;
                    sb.append("      ")
                            .append(infoHash.humanReadableValue())
                            .append(":")
                            .append("\n          ").append("current speed: ").append(humanReadableSpeed).append("/s")
                            .append("\n          ").append("overall upload: ").append(FileUtils.byteCountToDisplaySize(this.torrentsSeedStats.get(infoHash).getUploaded()))
                            .append("\n          ").append("weight: ").append(weightInPercent).append("% (").append(torrentWeight).append(" out of ").append(totalWeight).append(")")
                            .append("\n");
                });
                sb.setLength(sb.length() - 1); // remove last \n
                logger.debug(sb.toString());
            }
        } catch (final Exception e) {
            logger.debug("Error while printing debug message for speed.", e);
        }
    }

}
