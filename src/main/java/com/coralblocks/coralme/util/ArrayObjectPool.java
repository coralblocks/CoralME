package com.coralblocks.coralme.util;

import java.util.function.Supplier;

/**
 * An object pool backed by a preallocated array to minimize latency. Instances are created upfront but can also be created on demand if the pool is empty.
 *
 * <p><b>NOTE:</b> This data structure is designed to be used by <b>single-threaded systems</b>; it is not thread-safe.</p>
 *
 * <h2>Ultra-Low Latency Considerations:</h2>
 * <ul>
 *   <li><b>Preallocated Array:</b> Uses a preallocated array to improve cache locality and eliminate runtime allocations during normal operation.</li>
 *   <li><b>Supplier Instead of Reflection:</b> Utilizes a {@code Supplier<E>} for object creation to avoid the overhead associated with reflection and exception handling.</li>
 *   <li><b>Final Variables:</b> Class fields are declared as {@code final} where applicable to allow for potential compiler optimizations.</li>
 *   <li><b>Avoid Runtime Allocations:</b> The pool minimizes runtime allocations by reusing objects and only creating new ones when necessary.</li>
 *   <li><b>Simplified Methods:</b> Methods are kept short and straightforward to facilitate inlining and reduce method call overhead.</li>
 *   <li><b>No Exception Handling in Hot Paths:</b> Removes exception handling from methods that are likely to be on the critical path to eliminate potential latency spikes.</li>
 *   <li><b>No Synchronization:</b> Designed for single-threaded use, eliminating the need for synchronization primitives that could introduce latency.</li>
 * </ul>
 *
 * <h2>Potential Latency from New Instances:</h2>
 * <p>
 * Creating new instances at runtime can introduce latency due to memory allocation and initialization. To mitigate this:
 * </p>
 * <ul>
 *   <li><b>Size of the Pool:</b> Increase the initial size of the pool if you anticipate high demand, reducing the likelihood of creating new instances at runtime.</li>
 *   <li><b>Batch Allocation:</b> If creating new instances individually introduces unacceptable latency, consider batch-allocating new instances when the pool is empty.</li>
 * </ul>
 * <p>
 * Be aware that runtime allocations can lead to garbage collection (GC) pauses. While modern GCs are optimized, in ultra-low latency systems, even minor GC pauses can be problematic. If GC is a concern, consider using object recycling patterns.
 * </p>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Supplier for creating new instances
 * Supplier<MyObject> myObjectSupplier = MyObject::new;
 *
 * // Create an object pool with an initial size of 1000
 * ArrayObjectPool<MyObject> objectPool = new ArrayObjectPool<>(1000, myObjectSupplier);
 *
 * // Get an object from the pool
 * MyObject obj = objectPool.get();
 *
 * // Use the object
 * // ...
 *
 * // Release the object back to the pool
 * objectPool.release(obj);
 * }</pre>
 *
 * @param <E> the type of objects this object pool will hold
 */
public final class ArrayObjectPool<E> implements ObjectPool<E> {

    private final Object[] pool;
    private int index;
    private final LinkedObjectPool<E> backupPool;



    public ArrayObjectPool(int size, Supplier<E> supplier) {
        this(size, size, supplier);
    }

    /**
     * Creates an ArrayObjectPool with preallocated instances and a preallocated backup pool.
     *
     * @param size           the initial and maximum size of the pool
     * @param backupPoolSize the size of the backup pool
     * @param supplier       the supplier that will be used to create the instances
     */
    public ArrayObjectPool(int size, int backupPoolSize, Supplier<E> supplier) {

        pool = new Object[size];

        for (int i = 0; i < size; i++) {
            pool[i] = supplier.get();
        }

        index = size - 1;

        // Initialize backupPool with preallocated instances to avoid runtime allocation
        backupPool = new LinkedObjectPool<>(backupPoolSize, supplier);
    }

        /**
         * Gets an instance from the pool. If the pool is empty, it gets from the backup pool.
         *
         * @return an instance from the pool or backup pool
         */
    @Override
    public E get() {
        if (index >= 0) {
            return (E) pool[index--];
        }
        // Get from backupPool without creating new instances at runtime
        return backupPool.get();
    }

    /**
     * Returns an instance back to the pool. If the main pool is full, it releases to the backup pool.
     *
     * @param e the instance to return back to the pool
     */
    @Override
    public void release(E e) {
        if (index < pool.length - 1) {
            pool[ ++index] = e;
        } else {
            backupPool.release(e);
        }
    }

    /**
     * The total number of instances currently inside this pool and backup pool.
     *
     * @return the total number of instances currently sitting inside this pool
     */
    public int size() {
        return (index + 1) + backupPool.size();
    }
}

