/******************************************************************************
 *
 * Copyright (c) 2016, Cormac Flanagan (University of California, Santa Cruz) and Stephen Freund
 * (Williams College)
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions
 * and the following disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the names of the University of California, Santa Cruz and Williams College nor the names
 * of its contributors may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 ******************************************************************************/

package tools.fasttracksampling;

import acme.util.Assert;
import acme.util.Util;
import acme.util.count.AggregateCounter;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DecorationFactory.Type;
import acme.util.decorations.DefaultValue;
import acme.util.decorations.NullDefault;
import acme.util.io.XMLWriter;
import acme.util.option.CommandLine;
import rr.RRMain;
import rr.annotations.Abbrev;
import rr.barrier.BarrierEvent;
import rr.barrier.BarrierListener;
import rr.barrier.BarrierMonitor;
import rr.error.ErrorMessage;
import rr.error.ErrorMessages;
import rr.event.AccessEvent;
import rr.event.AccessEvent.Kind;
import rr.event.AcquireEvent;
import rr.event.ArrayAccessEvent;
import rr.event.ClassAccessedEvent;
import rr.event.ClassInitializedEvent;
import rr.event.FieldAccessEvent;
import rr.event.JoinEvent;
import rr.event.NewThreadEvent;
import rr.event.ReleaseEvent;
import rr.event.StartEvent;
import rr.event.VolatileAccessEvent;
import rr.event.WaitEvent;
import rr.instrument.classes.ArrayAllocSiteTracker;
import rr.meta.AccessInfo;
import rr.meta.ArrayAccessInfo;
import rr.meta.ClassInfo;
import rr.meta.FieldInfo;
import rr.meta.MetaDataInfoMaps;
import rr.meta.MethodInfo;
import rr.meta.OperationInfo;
import rr.meta.SourceLocation;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.util.Epoch;
import tools.util.VectorClock;

/*
 * A revised FastTrackSampling Tool. This makes several improvements over the original: - Simpler
 * synchronization scheme for VarStates. (The old optimistic scheme no longer has a performance
 * benefit and was hard to get right.) - Rephrased rules to: - include a Read-Shared-Same-Epoch
 * test. - eliminate an unnecessary update on joins (this was just for the proof). - remove the
 * Read-Shared to Exclusive transition. The last change makes the correctness argument easier and
 * that transition had little to no performance impact in practice. - Properly replays events when
 * the fast paths detect an error in all cases. - Supports long epochs for larger clock values. -
 * Handles tid reuse more precisely. The performance over the JavaGrande and DaCapo benchmarks is
 * more or less identical to the old implementation (within ~1% overall in our tests).
 */
@Abbrev("FT2S")
public class FastTrackSamplingTool extends Tool implements BarrierListener<FTSBarrierState> {

    private static final boolean COUNT_OPERATIONS = RRMain.slowMode();
    private static final int INIT_VECTOR_CLOCK_SIZE = 4;
    

    public static double EXP_FACTOR = Math.exp(-(double)1/100);
    public static double SAMPLING_RATE = rr.tool.RR.samplingRateOption.get(); 
    public static boolean SAMPLING_SCHEME_IS_COUNT = true;
    public int SAMPLED = 1;
    public int NOT_SAMPLED = 1;

    public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages
            .makeFieldErrorMessage("FastTrackSampling");
    public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages
            .makeArrayErrorMessage("FastTrackSampling");

    private final VectorClock maxEpochPerTid = new VectorClock(INIT_VECTOR_CLOCK_SIZE);

    // CS636: Every class object would have a vector clock. classInitTime is the Decoration which
    // stores ClassInfo (as a key) and corresponding vector clock for that class (as a value).
    // guarded by classInitTime
    public static final Decoration<ClassInfo, VectorClock> classInitTime = MetaDataInfoMaps
            .getClasses().makeDecoration("FastTrackSampling:ClassInitTime", Type.MULTIPLE,
                    new DefaultValue<ClassInfo, VectorClock>() {
                        public VectorClock get(ClassInfo st) {
                            return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
                        }
                    });

    public static VectorClock getClassInitTime(ClassInfo ci) {
        synchronized (classInitTime) {
            return classInitTime.get(ci);
        }
    }

    public FastTrackSamplingTool(final String name, final Tool next, CommandLine commandLine) {
        super(name, next, commandLine);

        new BarrierMonitor<FTSBarrierState>(this, new DefaultValue<Object, FTSBarrierState>() {
            public FTSBarrierState get(Object k) {
                return new FTSBarrierState(k, INIT_VECTOR_CLOCK_SIZE);
            }
        });
    }

    public void init(){
        SAMPLING_RATE = rr.tool.RR.samplingRateOption.get();
        SAMPLING_SCHEME_IS_COUNT = rr.tool.RR.samplingSchemeOption.get().equals("COUNT");
    }

    /*
     * Shadow State: St.E -- epoch decoration on ShadowThread - Thread-local. Never access from a
     * different thread St.V -- VectorClock decoration on ShadowThread - Thread-local while thread
     * is running. - The thread starting t may access st.V before the start. - Any thread joining on
     * t may read st.V after the join. Sm.V -- FTSLockState decoration on ShadowLock - See
     * FTSLockState for synchronization rules. Sx.R,Sx.W,Sx.V -- FTSVarState objects - See FTSVarState
     * for synchronization rules. Svx.V -- FTSVolatiletate decoration on ShadowVolatile (serves same
     * purpose as L for volatiles) - See FTSVolatiletate for synchronization rules. Sb.V --
     * FTSBarrierState decoration on Barriers - See FTSBarrierState for synchronization rules.
     */

    // invariant: st.E == st.V(st.tid)
    protected static int/* epoch */ ts_get_E(ShadowThread st) {
        Assert.panic("Bad");
        return -1;
    }

    protected static void ts_set_E(ShadowThread st, int/* epoch */ e) {
        Assert.panic("Bad");
    }

    protected static VectorClock ts_get_V(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    protected static void ts_set_V(ShadowThread st, VectorClock V) {
        Assert.panic("Bad");
    }

    protected void maxAndIncEpochAndCV(ShadowThread st, VectorClock other, OperationInfo info) {
        final int tid = st.getTid();
        final VectorClock tV = ts_get_V(st);
        tV.max(other);
        tV.tick(tid);
        ts_set_E(st, tV.get(tid));
    }

    protected void maxEpochAndCV(ShadowThread st, VectorClock other, OperationInfo info) {
        final int tid = st.getTid();
        final VectorClock tV = ts_get_V(st);
        tV.max(other);
        ts_set_E(st, tV.get(tid));
    }

    protected void incEpochAndCV(ShadowThread st, OperationInfo info) {
        final int tid = st.getTid();
        final VectorClock tV = ts_get_V(st);
        tV.tick(tid);
        ts_set_E(st, tV.get(tid));
    }

    static final Decoration<ShadowLock, FTSLockState> lockVs = ShadowLock.makeDecoration(
            "FastTrackSampling:ShadowLock", DecorationFactory.Type.MULTIPLE,
            new DefaultValue<ShadowLock, FTSLockState>() {
                public FTSLockState get(final ShadowLock lock) {
                    return new FTSLockState(lock, INIT_VECTOR_CLOCK_SIZE);
                }
            });

    // only call when ld.peer() is held
    static final FTSLockState getV(final ShadowLock ld) {
        return lockVs.get(ld);
    }

    static final Decoration<ShadowVolatile, FTSVolatileState> volatileVs = ShadowVolatile
            .makeDecoration("FastTrackSampling:shadowVolatile", DecorationFactory.Type.MULTIPLE,
                    new DefaultValue<ShadowVolatile, FTSVolatileState>() {
                        public FTSVolatileState get(final ShadowVolatile vol) {
                            return new FTSVolatileState(vol, INIT_VECTOR_CLOCK_SIZE);
                        }
                    });

    // only call when we are in an event handler for the volatile field.
    protected static final FTSVolatileState getV(final ShadowVolatile ld) {
        return volatileVs.get(ld);
    }

    @Override
    public ShadowVar makeShadowVar(final AccessEvent event) {
        if (event.getKind() == Kind.VOLATILE) {
            final ShadowThread st = event.getThread();
            final VectorClock volV = getV(((VolatileAccessEvent) event).getShadowVolatile());
            volV.max(ts_get_V(st));
            return super.makeShadowVar(event);
        } else {
            return new FTSVarState(event.isWrite(), ts_get_E(event.getThread()));
        }
    }

    @Override
    public void create(NewThreadEvent event) {
        final ShadowThread st = event.getThread();

        if (ts_get_V(st) == null) {
            final int tid = st.getTid();
            final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
            ts_set_V(st, tV);
            synchronized (maxEpochPerTid) {
                final int/* epoch */ epoch = maxEpochPerTid.get(tid) + 1;
                tV.set(tid, epoch);
                ts_set_E(st, epoch);
            }
            incEpochAndCV(st, null);
            Util.log("Initial E for " + tid + ": " + Epoch.toString(ts_get_E(st)));
        }

        super.create(event);
    }

    @Override
    public void acquire(final AcquireEvent event) {
        final ShadowThread st = event.getThread();
        final FTSLockState lockV = getV(event.getLock());

        maxEpochAndCV(st, lockV, event.getInfo());

        super.acquire(event);
        if (COUNT_OPERATIONS)
            acquire.inc(st.getTid());
    }

    @Override
    public void release(final ReleaseEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock tV = ts_get_V(st);
        final VectorClock lockV = getV(event.getLock());

        lockV.max(tV);
        incEpochAndCV(st, event.getInfo());

        super.release(event);
        if (COUNT_OPERATIONS)
            release.inc(st.getTid());
    }

    static FTSVarState ts_get_badVarState(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    static void ts_set_badVarState(ShadowThread st, FTSVarState v) {
        Assert.panic("Bad");
    }

    protected static ShadowVar getOriginalOrBad(ShadowVar original, ShadowThread st) {
        final FTSVarState savedState = ts_get_badVarState(st);
        if (savedState != null) {
            ts_set_badVarState(st, null);
            return savedState;
        } else {
            return original;
        }
    }

    @Override
    public void access(final AccessEvent event) {
        SourceLocation sl = event.getAccessInfo().getLoc();
        int line = sl.getLine();
        int offset = sl.getOffset();
        MethodInfo methInfo = sl.getMethod();
        String methName = methInfo.getName();
        ClassInfo className = methInfo.getOwner();
        String desc = methInfo.getDescriptor();

        final ShadowThread st = event.getThread();
        final ShadowVar shadow = getOriginalOrBad(event.getOriginalShadow(), st);
   
        if (shadow instanceof FTSVarState) {
            FTSVarState sx = (FTSVarState) shadow;
            
            boolean check;
            

            if(SAMPLING_SCHEME_IS_COUNT){
                check = (SAMPLED*(100-SAMPLING_RATE)) < (NOT_SAMPLED*SAMPLING_RATE);            
            }else{
                check = (sx.SAMPLED*(100-sx.SAMPLING_RATE)) < (sx.NOT_SAMPLED*sx.SAMPLING_RATE);
                sx.SAMPLING_RATE = sx.SAMPLING_RATE*EXP_FACTOR;
                sx.SAMPLING_RATE = Math.max(sx.SAMPLING_RATE, sx.THRESHOLD);
            }

            if(check){
                sx.SAMPLED++;   
                SAMPLED++;
                Object target = event.getTarget();
                if (target == null) {
                    // CS636: Static variable
                    ClassInfo owner = ((FieldAccessEvent) event).getInfo().getField().getOwner();
                    final VectorClock tV = ts_get_V(st);
                    synchronized (classInitTime) {
                        VectorClock initTime = classInitTime.get(owner);
                        maxEpochAndCV(st, initTime, event.getAccessInfo()); // won't change current
                                                                            // epoch
                    }
                }

                if (event.isWrite()) {
                    write(event, st, sx);
                } else {
                    read(event, st, sx);
                }
            }
            else{
            sx.NOT_SAMPLED++;
            NOT_SAMPLED++;
            }
            
        } else {
            super.access(event);
        }
    
    }

    // Counters for relative frequencies of each rule
    private static final ThreadLocalCounter readSameEpoch = new ThreadLocalCounter("FTS",
            "Read Same Epoch", RR.maxTidOption.get());
    private static final ThreadLocalCounter readSharedSameEpoch = new ThreadLocalCounter("FTS",
            "ReadShared Same Epoch", RR.maxTidOption.get());
    private static final ThreadLocalCounter readExclusive = new ThreadLocalCounter("FTS",
            "Read Exclusive", RR.maxTidOption.get());
    private static final ThreadLocalCounter readShare = new ThreadLocalCounter("FTS", "Read Share",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter readShared = new ThreadLocalCounter("FTS", "Read Shared",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter writeReadError = new ThreadLocalCounter("FTS",
            "Write-Read Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeSameEpoch = new ThreadLocalCounter("FTS",
            "Write Same Epoch", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeExclusive = new ThreadLocalCounter("FTS",
            "Write Exclusive", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeShared = new ThreadLocalCounter("FTS",
            "Write Shared", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeWriteError = new ThreadLocalCounter("FTS",
            "Write-Write Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter readWriteError = new ThreadLocalCounter("FTS",
            "Read-Write Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter sharedWriteError = new ThreadLocalCounter("FTS",
            "Shared-Write Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter acquire = new ThreadLocalCounter("FTS", "Acquire",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter release = new ThreadLocalCounter("FTS", "Release",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter fork = new ThreadLocalCounter("FTS", "Fork",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter join = new ThreadLocalCounter("FTS", "Join",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter barrier = new ThreadLocalCounter("FTS", "Barrier",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter wait = new ThreadLocalCounter("FTS", "Wait",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter vol = new ThreadLocalCounter("FTS", "Volatile",
            RR.maxTidOption.get());

    private static final ThreadLocalCounter other = new ThreadLocalCounter("FTS", "Other",
            RR.maxTidOption.get());

    static {
        AggregateCounter reads = new AggregateCounter("FTS", "Total Reads", readSameEpoch,
                readSharedSameEpoch, readExclusive, readShare, readShared, writeReadError);
        AggregateCounter writes = new AggregateCounter("FTS", "Total Writes", writeSameEpoch,
                writeExclusive, writeShared, writeWriteError, readWriteError, sharedWriteError);
        AggregateCounter accesses = new AggregateCounter("FTS", "Total Access Ops", reads, writes);
        new AggregateCounter("FTS", "Total Ops", accesses, acquire, release, fork, join, barrier,
                wait, vol, other);
    }

    protected void read(final AccessEvent event, final ShadowThread st, final FTSVarState sx) {
                final int/* epoch */ e = ts_get_E(st);

                /* optional */ {
                    final int/* epoch */ r = sx.R;
                    if (r == e) {
                        if (COUNT_OPERATIONS)
                            readSameEpoch.inc(st.getTid());
                        return;
                    } else if (r == Epoch.READ_SHARED && sx.get(st.getTid()) == e) {
                        if (COUNT_OPERATIONS)
                            readSharedSameEpoch.inc(st.getTid());
                        return;
                    }
                }

                synchronized (sx) {
                    final VectorClock tV = ts_get_V(st);
                    final int/* epoch */ r = sx.R;
                    final int/* epoch */ w = sx.W;
                    final int wTid = Epoch.tid(w);
                    final int tid = st.getTid();

                    if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
                        if (COUNT_OPERATIONS)
                            writeReadError.inc(tid);
                        error(event, sx, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
                        // best effort recovery:
                        return;
                    }

                    if (r != Epoch.READ_SHARED) {
                        final int rTid = Epoch.tid(r);
                        if (rTid == tid || Epoch.leq(r, tV.get(rTid))) {
                            if (COUNT_OPERATIONS)
                                readExclusive.inc(tid);
                            sx.R = e;
                        } else {
                            if (COUNT_OPERATIONS)
                                readShare.inc(tid);
                            int initSize = Math.max(Math.max(rTid, tid), INIT_VECTOR_CLOCK_SIZE);
                            sx.makeCV(initSize);
                            sx.set(rTid, r);
                            sx.set(tid, e);
                            sx.R = Epoch.READ_SHARED;
                        }
                    } else {
                        if (COUNT_OPERATIONS)
                            readShared.inc(tid);
                        sx.set(tid, e);
                    }
                }
    }

    // CS636: Commented the method to prevent inlining of the read barrier
    // public static boolean readFastPath(final ShadowVar shadow, final ShadowThread st) {
    // if (shadow instanceof FTSVarState) {
    // final FTSVarState sx = ((FTSVarState) shadow);

    // final int/* epoch */ e = ts_get_E(st);

    // /* optional */ {
    // final int/* epoch */ r = sx.R;
    // if (r == e) {
    // if (COUNT_OPERATIONS)
    // readSameEpoch.inc(st.getTid());
    // return true;
    // } else if (r == Epoch.READ_SHARED && sx.get(st.getTid()) == e) {
    // if (COUNT_OPERATIONS)
    // readSharedSameEpoch.inc(st.getTid());
    // return true;
    // }
    // }

    // synchronized (sx) {
    // final int tid = st.getTid();
    // final VectorClock tV = ts_get_V(st);
    // final int/* epoch */ r = sx.R;
    // final int/* epoch */ w = sx.W;
    // final int wTid = Epoch.tid(w);
    // if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }

    // if (r != Epoch.READ_SHARED) {
    // final int rTid = Epoch.tid(r);
    // if (rTid == tid || Epoch.leq(r, tV.get(rTid))) {
    // if (COUNT_OPERATIONS)
    // readExclusive.inc(tid);
    // sx.R = e;
    // } else {
    // if (COUNT_OPERATIONS)
    // readShare.inc(tid);
    // int initSize = Math.max(Math.max(rTid, tid), INIT_VECTOR_CLOCK_SIZE);
    // sx.makeCV(initSize);
    // sx.set(rTid, r);
    // sx.set(tid, e);
    // sx.R = Epoch.READ_SHARED;
    // }
    // } else {
    // if (COUNT_OPERATIONS)
    // readShared.inc(tid);
    // sx.set(tid, e);
    // }
    // return true;
    // }
    // } else {
    // return false;
    // }
    // }

    /***/

    protected void write(final AccessEvent event, final ShadowThread st, final FTSVarState sx) {
                final int/* epoch */ e = ts_get_E(st);

                /* optional */ {
                    final int/* epoch */ w = sx.W;
                    if (w == e) {
                        if (COUNT_OPERATIONS)
                            writeSameEpoch.inc(st.getTid());
                        return;
                    }
                }

                synchronized (sx) {
                    final int/* epoch */ w = sx.W;
                    final int wTid = Epoch.tid(w);
                    final int tid = st.getTid();
                    final VectorClock tV = ts_get_V(st);

                    if (wTid != tid /* optimization */ && !Epoch.leq(w, tV.get(wTid))) {
                        if (COUNT_OPERATIONS)
                            writeWriteError.inc(tid);
                        error(event, sx, "Write-Write Race", "Write by ", wTid, "Write by ", tid);
                    }

                    final int/* epoch */ r = sx.R;
                    if (r != Epoch.READ_SHARED) {
                        final int rTid = Epoch.tid(r);
                        if (rTid != tid /* optimization */ && !Epoch.leq(r, tV.get(rTid))) {
                            if (COUNT_OPERATIONS)
                                readWriteError.inc(tid);
                            error(event, sx, "Read-Write Race", "Read by ", rTid, "Write by ", tid);
                        } else {
                            if (COUNT_OPERATIONS)
                                writeExclusive.inc(tid);
                        }
                    } else {
                        if (sx.anyGt(tV)) {
                            for (int prevReader = sx.nextGt(tV, 0); prevReader > -1; prevReader = sx
                                    .nextGt(tV, prevReader + 1)) {
                                error(event, sx, "Read(Shared)-Write Race", "Read by ", prevReader,
                                        "Write by ", tid);
                            }
                            if (COUNT_OPERATIONS)
                                sharedWriteError.inc(tid);
                        } else {
                            if (COUNT_OPERATIONS)
                                writeShared.inc(tid);
                        }
                    }
                    sx.W = e;
                }
    }

    // CS636: Commented the method to prevent inlining of the read barrier
    // only count events when returning true;
    // public static boolean writeFastPath(final ShadowVar shadow, final ShadowThread st) {
    // if (shadow instanceof FTSVarState) {
    // final FTSVarState sx = ((FTSVarState) shadow);

    // final int/* epoch */ E = ts_get_E(st);

    // /* optional */ {
    // final int/* epoch */ w = sx.W;
    // if (w == E) {
    // if (COUNT_OPERATIONS)
    // writeSameEpoch.inc(st.getTid());
    // return true;
    // }
    // }

    // synchronized (sx) {
    // final int tid = st.getTid();
    // final int/* epoch */ w = sx.W;
    // final int wTid = Epoch.tid(w);
    // final VectorClock tV = ts_get_V(st);

    // if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }

    // final int/* epoch */ r = sx.R;
    // if (r != Epoch.READ_SHARED) {
    // final int rTid = Epoch.tid(r);
    // if (rTid != tid && !Epoch.leq(r, tV.get(rTid))) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }
    // if (COUNT_OPERATIONS)
    // writeExclusive.inc(tid);
    // } else {
    // if (sx.anyGt(tV)) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }
    // if (COUNT_OPERATIONS)
    // writeShared.inc(tid);
    // }
    // sx.W = E;
    // return true;
    // }
    // } else {
    // return false;
    // }
    // }

    /*****/

    @Override
    public void volatileAccess(final VolatileAccessEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock volV = getV((event).getShadowVolatile());

        if (event.isWrite()) {
            final VectorClock tV = ts_get_V(st);
            volV.max(tV);
            incEpochAndCV(st, event.getAccessInfo());
        } else {
            maxEpochAndCV(st, volV, event.getAccessInfo());
        }

        super.volatileAccess(event);
        if (COUNT_OPERATIONS)
            vol.inc(st.getTid());
    }

    // st forked su
    @Override
    public void preStart(final StartEvent event) {
        final ShadowThread st = event.getThread();
        final ShadowThread su = event.getNewThread();
        final VectorClock tV = ts_get_V(st);

        /*
         * Safe to access su.V, because u has not started yet. This will give us exclusive access to
         * it. There may be a race if two or more threads race are starting u, but of course, a
         * second attempt to start u will crash... RR guarantees that the forked thread will
         * synchronize with thread t before it does anything else.
         */
        maxAndIncEpochAndCV(su, tV, event.getInfo());
        incEpochAndCV(st, event.getInfo());

        super.preStart(event);
        if (COUNT_OPERATIONS)
            fork.inc(st.getTid());
    }

    @Override
    public void stop(ShadowThread st) {
        synchronized (maxEpochPerTid) {
            maxEpochPerTid.set(st.getTid(), ts_get_E(st));
        }
        super.stop(st);
        if (COUNT_OPERATIONS)
            other.inc(st.getTid());
    }

    // t joined on u
    @Override
    public void postJoin(final JoinEvent event) {
        final ShadowThread st = event.getThread();
        final ShadowThread su = event.getJoiningThread();

        // move our clock ahead. Safe to access su.V, as above, when
        // lock is held and u is not running. Also, RR guarantees
        // this thread has sync'd with u.

        maxEpochAndCV(st, ts_get_V(su), event.getInfo());
        // no need to inc su's clock here -- that was just for
        // the proof in the original FastTrackSampling rules.

        super.postJoin(event);
        if (COUNT_OPERATIONS)
            join.inc(st.getTid());
    }

    @Override
    public void preWait(WaitEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock lockV = getV(event.getLock());
        lockV.max(ts_get_V(st)); // we hold lock, so no need to sync here...
        incEpochAndCV(st, event.getInfo());
        super.preWait(event);
        if (COUNT_OPERATIONS)
            wait.inc(st.getTid());
    }

    @Override
    public void postWait(WaitEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock lockV = getV(event.getLock());
        maxEpochAndCV(st, lockV, event.getInfo()); // we hold lock here
        super.postWait(event);
        if (COUNT_OPERATIONS)
            wait.inc(st.getTid());
    }

    public static String toString(final ShadowThread td) {
        return String.format("[tid=%-2d   C=%s   E=%s]", td.getTid(), ts_get_V(td),
                Epoch.toString(ts_get_E(td)));
    }

    private final Decoration<ShadowThread, VectorClock> vectorClockForBarrierEntry = ShadowThread
            .makeDecoration("FTS:barrier", DecorationFactory.Type.MULTIPLE,
                    new NullDefault<ShadowThread, VectorClock>());

    public void preDoBarrier(BarrierEvent<FTSBarrierState> event) {
        final ShadowThread st = event.getThread();
        final FTSBarrierState barrierObj = event.getBarrier();
        synchronized (barrierObj) {
            final VectorClock barrierV = barrierObj.enterBarrier();
            barrierV.max(ts_get_V(st));
            vectorClockForBarrierEntry.set(st, barrierV);
        }
        if (COUNT_OPERATIONS)
            barrier.inc(st.getTid());
    }

    public void postDoBarrier(BarrierEvent<FTSBarrierState> event) {
        final ShadowThread st = event.getThread();
        final FTSBarrierState barrierObj = event.getBarrier();
        synchronized (barrierObj) {
            final VectorClock barrierV = vectorClockForBarrierEntry.get(st);
            barrierObj.stopUsingOldVectorClock(barrierV);
            maxAndIncEpochAndCV(st, barrierV, null);
        }
        if (COUNT_OPERATIONS)
            barrier.inc(st.getTid());
    }

    ///

    @Override
    public void classInitialized(ClassInitializedEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock tV = ts_get_V(st);
        synchronized (classInitTime) {
            VectorClock initTime = classInitTime.get(event.getRRClass());
            initTime.copy(tV);
        }
        incEpochAndCV(st, null);
        super.classInitialized(event);
        if (COUNT_OPERATIONS)
            other.inc(st.getTid());
    }

    @Override
    public void classAccessed(ClassAccessedEvent event) {
        final ShadowThread st = event.getThread();
        synchronized (classInitTime) {
            final VectorClock initTime = classInitTime.get(event.getRRClass());
            maxEpochAndCV(st, initTime, null);
        }
        if (COUNT_OPERATIONS)
            other.inc(st.getTid());
    }

    @Override
    public void printXML(XMLWriter xml) {
        for (ShadowThread td : ShadowThread.getThreads()) {
            xml.print("thread", toString(td));
        }
    }

    protected void error(final AccessEvent ae, final FTSVarState x, final String description,
            final String prevOp, final int prevTid, final String curOp, final int curTid) {

        if (ae instanceof FieldAccessEvent) {
            fieldError((FieldAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
        } else {
            arrayError((ArrayAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
        }
    }

    protected void arrayError(final ArrayAccessEvent aae, final FTSVarState sx,
            final String description, final String prevOp, final int prevTid, final String curOp,
            final int curTid) {
        final ShadowThread st = aae.getThread();
        final Object target = aae.getTarget();

        if (arrayErrors.stillLooking(aae.getInfo())) {
            arrayErrors.error(st, aae.getInfo(), "Alloc Site", ArrayAllocSiteTracker.get(target),
                    "Shadow State", sx, "Current Thread", toString(st), "Array",
                    Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]", "Message",
                    description, "Previous Op", prevOp + " " + ShadowThread.get(prevTid),
                    "Currrent Op", curOp + " " + ShadowThread.get(curTid), "Stack",
                    ShadowThread.stackDumpForErrorMessage(st));
        }
        Assert.assertTrue(prevTid != curTid);

        aae.getArrayState().specialize();

        if (!arrayErrors.stillLooking(aae.getInfo())) {
            advance(aae);
        }
    }

    protected void fieldError(final FieldAccessEvent fae, final FTSVarState sx,
            final String description, final String prevOp, final int prevTid, final String curOp,
            final int curTid) {
        final FieldInfo fd = fae.getInfo().getField();
        final ShadowThread st = fae.getThread();
        final Object target = fae.getTarget();

        if (fieldErrors.stillLooking(fd)) {
            fieldErrors.error(st, fd, "Shadow State", sx, "Current Thread", toString(st), "Class",
                    (target == null ? fd.getOwner() : target.getClass()), "Field",
                    Util.objectToIdentityString(target) + "." + fd, "Message", description,
                    "Previous Op", prevOp + " " + ShadowThread.get(prevTid), "Currrent Op",
                    curOp + " " + ShadowThread.get(curTid), "Stack",
                    ShadowThread.stackDumpForErrorMessage(st));
        }

        Assert.assertTrue(prevTid != curTid);

        if (!fieldErrors.stillLooking(fd)) {
            advance(fae);
        }
    }
}
