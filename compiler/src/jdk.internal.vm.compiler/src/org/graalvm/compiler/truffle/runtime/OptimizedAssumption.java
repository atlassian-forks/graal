/*
 * Copyright (c) 2013, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.truffle.runtime;

import java.util.function.Consumer;
import java.util.logging.Level;

import org.graalvm.compiler.truffle.common.OptimizedAssumptionDependency;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.impl.AbstractAssumption;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;

import jdk.vm.ci.meta.JavaKind.FormatWithToString;

/**
 * An assumption that when {@linkplain #invalidate() invalidated} will cause all
 * {@linkplain #registerDependency() registered} dependencies to be invalidated.
 */
public final class OptimizedAssumption extends AbstractAssumption implements FormatWithToString {

    /**
     * Reference to machine code that is dependent on an assumption.
     */
    static class Entry implements Consumer<OptimizedAssumptionDependency> {
        /**
         * A machine code reference that must be kept reachable as long as the machine code itself
         * is valid.
         */
        OptimizedAssumptionDependency dependency;
        boolean pending = true;

        Entry next;

        @Override
        public synchronized void accept(OptimizedAssumptionDependency dep) {
            this.dependency = dep;
            this.pending = false;
            this.notifyAll();
        }

        synchronized OptimizedAssumptionDependency awaitDependency() {
            boolean interrupted = false;
            while (pending) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return dependency;
        }

        synchronized boolean isAlive() {
            if (dependency == null) {
                // A pending dependency is treated as alive
                return pending;
            }
            return dependency.isAlive();
        }

        @Override
        public synchronized String toString() {
            if (dependency != null) {
                return String.format("%x[%s]", hashCode(), dependency);
            }
            return String.format("%x", hashCode());
        }
    }

    /**
     * Linked list of registered dependencies.
     */
    private Entry dependencies;

    /**
     * Number of entries in {@link #dependencies}.
     */
    private int size;

    /**
     * Number of entries in {@link #dependencies} after most recent call to
     * {@link #removeDeadEntries()}.
     */
    private int sizeAfterLastRemove;

    public OptimizedAssumption(String name) {
        super(name);
    }

    private OptimizedAssumption(Object name) {
        super(name);
    }

    static Assumption createAlwaysValid() {
        return new OptimizedAssumption(Lazy.ALWAYS_VALID_NAME);
    }

    @Override
    public void check() throws InvalidAssumptionException {
        if (!this.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new InvalidAssumptionException();
        }
    }

    @Override
    public void invalidate() {
        if (isValid) {
            invalidateImpl("");
        }
    }

    @Override
    public void invalidate(String message) {
        if (isValid) {
            invalidateImpl(message);
        }
    }

    @TruffleBoundary
    private synchronized void invalidateImpl(String message) {
        /*
         * Check again, now that we are holding the lock. Since isValid is defined volatile,
         * double-checked locking is allowed.
         */
        if (!isValid) {
            return;
        }

        if (this.name == Lazy.ALWAYS_VALID_NAME) {
            throw new UnsupportedOperationException("Cannot invalidate this assumption - it is always valid");
        }

        OptionValues engineOptions = null;
        TruffleLogger logger = null;
        boolean logStackTrace = false;

        Entry e = dependencies;
        CharSequence reason = null;
        while (e != null) {
            OptimizedAssumptionDependency dependency = e.awaitDependency();
            if (dependency != null) {
                if (reason == null) {
                    String useName = name != null ? name.toString() : "";
                    String useMessage = message != null ? message : "";
                    if (useName.isEmpty() && useMessage.isEmpty()) {
                        reason = "assumption invalidated";
                    } else if (useName.isEmpty()) {
                        reason = useMessage;
                    } else if (useMessage.isEmpty()) {
                        reason = useName;
                    } else {
                        reason = new LazyReason(useName, useMessage);
                    }
                }
                dependency.onAssumptionInvalidated(this, reason);

                if (engineOptions == null) {
                    OptimizedCallTarget callTarget = (OptimizedCallTarget) dependency.getCompilable();
                    if (callTarget != null) {
                        engineOptions = callTarget.getOptionValues();
                        logger = callTarget.engine.getEngineLogger();
                    } else {
                        EngineData engineData = GraalTVMCI.getEngineData(null);
                        engineOptions = engineData.engineOptions;
                        logger = engineData.getEngineLogger();
                    }
                }

                if (engineOptions.get(OptimizedRuntimeOptions.TraceAssumptions)) {
                    logStackTrace = true;
                    logInvalidatedDependency(dependency, message, logger);
                }
            }
            e = e.next;
        }
        dependencies = null;
        size = 0;
        sizeAfterLastRemove = 0;
        isValid = false;

        if (logStackTrace) {
            logStackTrace(engineOptions, logger);
        }
    }

    private void removeDeadEntries() {
        Entry last = null;
        Entry e = dependencies;
        dependencies = null;
        while (e != null) {
            if (e.isAlive()) {
                if (last == null) {
                    dependencies = e;
                } else {
                    last.next = e;
                }
                last = e;
            } else {
                size--;
            }
            e = e.next;
        }
        if (last != null) {
            last.next = null;
        }
        sizeAfterLastRemove = size;
    }

    /**
     * Removes all {@linkplain OptimizedAssumptionDependency#isAlive() invalid} dependencies.
     */
    public synchronized void removeDeadDependencies() {
        removeDeadEntries();
    }

    /**
     * Gets the number of dependencies registered with this assumption.
     */
    public synchronized int countDependencies() {
        return size;
    }

    private static final Consumer<OptimizedAssumptionDependency> DISCARD_DEPENDENCY = (e) -> {
    };

    /**
     * Registers some dependent code with this assumption.
     *
     * As the dependent code may not yet be available, a {@link Consumer} is returned that must be
     * {@linkplain Consumer#accept(Object) notified} when the code becomes available. If there is an
     * error while compiling or installing the code, the returned consumer must be called with a
     * {@code null} argument.
     *
     * If this assumption is already invalid, then {@code null} is returned in which case the caller
     * (e.g., the compiler) must ensure the dependent code is never executed.
     */
    public synchronized Consumer<OptimizedAssumptionDependency> registerDependency() {
        if (isValid) {
            if (this.name == Lazy.ALWAYS_VALID_NAME) {
                /*
                 * An ALWAYS_VALID assumption does not need registration, as they are by definition
                 * always valid. If they attempted to get invalidated an error is thrown, so we can
                 * just discard the dependency.
                 */
                return DISCARD_DEPENDENCY;
            }
            if (size >= 2 * sizeAfterLastRemove) {
                removeDeadEntries();
            }
            Entry e = new Entry();
            e.next = dependencies;
            dependencies = e;
            size++;
            return e;
        } else {
            return null;
        }
    }

    @Override
    public boolean isValid() {
        return isValid;
    }

    private void logInvalidatedDependency(OptimizedAssumptionDependency dependency, String message, TruffleLogger logger) {
        final StringBuilder sb = new StringBuilder("assumption '").append(name).append("' invalidated installed code '").append(dependency);
        if (message != null && !message.isEmpty()) {
            sb.append("' with message '").append(message);
        }
        logger.log(Level.INFO, sb.append("'").toString());
    }

    private static void logStackTrace(OptionValues engineOptions, TruffleLogger logger) {
        final int skip = 1;
        final int limit = engineOptions.get(OptimizedRuntimeOptions.TraceStackTraceLimit);
        StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        StringBuilder strb = new StringBuilder();
        String sep = "";
        for (int i = skip; i < stackTrace.length && i < skip + limit; i++) {
            strb.append(sep).append("  ").append(stackTrace[i].toString());
            sep = "\n";
        }
        if (stackTrace.length > skip + limit) {
            strb.append("\n    ...");
        }

        logger.log(Level.INFO, strb.toString());
    }

    private static final class LazyReason implements CharSequence {

        private final String assumptionName;
        private final String message;
        private String strValue;

        LazyReason(String assumptionName, String message) {
            this.assumptionName = assumptionName;
            this.message = message;
        }

        @Override
        public int length() {
            return toString().length();
        }

        @Override
        public char charAt(int index) {
            return toString().charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return toString().subSequence(start, end);
        }

        @Override
        public String toString() {
            if (strValue == null) {
                strValue = assumptionName + ' ' + message;
            }
            return strValue;
        }
    }

    /*
     * We use a lazy class as this is already needed when the assumption is initialized.
     */
    static class Lazy {
        /*
         * We use an Object instead of a String here to avoid accidently handing out the always
         * valid string object in getName().
         */
        static final Object ALWAYS_VALID_NAME = new Object() {
            @Override
            public String toString() {
                return "<always valid>";
            }
        };
    }

}
