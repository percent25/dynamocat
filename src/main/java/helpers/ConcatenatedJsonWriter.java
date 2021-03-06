package helpers;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import com.google.common.base.Defaults;
import com.google.common.base.MoreObjects;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonElement;

/**
 * ConcatenatedJsonWriter
 * 
 * <p>not thread-safe
 */
public class ConcatenatedJsonWriter {

    public interface Transport {
        /**
         * maximum transmission unit
         */
        int mtu();

        /**
         * send bytes
         * 
         * <p>
         * ConcatenatedJsonWriter shall not ask Transport to send more than mtu bytes
         */
        ListenableFuture<?> send(byte[] bytes);
    }

    private class VoidFuture extends AbstractFuture<Void> {
        public boolean setVoid() {
            return super.set(Defaults.defaultValue(Void.class));
        }

        public boolean setException(Throwable throwable) {
            return super.setException(throwable);
        }
    }

    // abstract write transport
    private final Transport transport;

    // current partition
    private ByteArrayOutputStream baos = new ByteArrayOutputStream();

    // partition -> futures
    private final Multimap<ByteArrayOutputStream, VoidFuture> partitions = LinkedListMultimap.create();
    
    private final List<ListenableFuture<?>> flushFutures = Lists.newArrayList();

    /**
     * ctor
     * 
     * @param transport
     */
    public ConcatenatedJsonWriter(Transport transport) {
        debug("ctor", transport);
        this.transport = transport;
    }

    public String toString() {
        return MoreObjects.toStringHelper(this).add("transport", transport).toString();
    }

    /**
     * write
     * 
     * @param jsonElement
     * @return
     */
    public ListenableFuture<?> write(JsonElement jsonElement) {
        trace("write", jsonElement);
        return new FutureRunner() {
            {
                run(() -> {
                    byte[] bytes = render(jsonElement);
                    if (bytes.length > transport.mtu())
                        throw new IllegalArgumentException("jsonElement more than mtu");
                    if (baos.size() + bytes.length > transport.mtu())
                        baos = flush(baos, partitions.get(baos));
                    baos.write(bytes, 0, bytes.length);

                    VoidFuture lf = new VoidFuture();
                    partitions.put(baos, lf); // track futures on a per-baos/batch/partition basis
                    return lf;
                });
            }
        };
    }

    /**
     * flush
     * 
     * @return
     */
    public ListenableFuture<?> flush() {
        debug("flush");
        return new FutureRunner() {
            {
                run(() -> {
                    if (baos.size() > 0)
                        baos = flush(baos, partitions.get(baos));
                    return Futures.successfulAsList(flushFutures);
                });
            }
        };
    }

    // returns new baos
    private ByteArrayOutputStream flush(ByteArrayOutputStream baos, Iterable<VoidFuture> partition) {
        debug("flush", baos.size());
        ListenableFuture<?> lf = new FutureRunner() {
            {
                run(() -> {
                    // request
                    return transport.send(baos.toByteArray());
                }, sendResponse -> {
                    // success
                    partition.forEach(lf -> lf.setVoid());
                }, e -> {
                    // failure
                    partition.forEach(lf -> lf.setException(e));
                });
            }
        };
        flushFutures.add(lf);
        return new ByteArrayOutputStream();
    }

    private byte[] render(JsonElement jsonElement) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new PrintStream(baos, true).println(jsonElement);
        return baos.toByteArray();
    }

    private void debug(Object... args) {
        new LogHelper(this).debug(args);
    }

    private void trace(Object... args) {
        new LogHelper(this).trace(args);
    }

}