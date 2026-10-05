package ru.mcs.sysmon.collector;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import ru.mcs.sysmon.model.ProcessSample;
import ru.mcs.sysmon.model.Sample;
import ru.mcs.sysmon.model.SystemSample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads OSHI and turns cumulative counters into rates. The constructor takes the baseline,
 * so every call to {@link #sample()} reports the interval since the previous call.
 */
public final class Sampler {

    private static final long MB = 1024L * 1024L;

    private final OperatingSystem os;
    private final CentralProcessor cpu;
    private final GlobalMemory mem;
    private final List<HWDiskStore> disks;
    private final int logical;

    private long[] prevTicks;
    private long prevNanos;
    private Map<Integer, OSProcess> prevProcs;
    private final long[] prevTransfer;
    private final long[] prevRead;
    private final long[] prevWrite;
    private long prevPagesIn;
    private long prevPagesOut;

    public Sampler() {
        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hal = si.getHardware();
        os = si.getOperatingSystem();
        cpu = hal.getProcessor();
        mem = hal.getMemory();
        disks = hal.getDiskStores();
        logical = cpu.getLogicalProcessorCount();
        prevTransfer = new long[disks.size()];
        prevRead = new long[disks.size()];
        prevWrite = new long[disks.size()];

        prevTicks = cpu.getSystemCpuLoadTicks();
        prevProcs = snapshot();
        pollDisks(1.0);
        VirtualMemory vm = mem.getVirtualMemory();
        prevPagesIn = vm.getSwapPagesIn();
        prevPagesOut = vm.getSwapPagesOut();
        prevNanos = System.nanoTime();
    }

    public Sample sample() {
        long now = System.nanoTime();
        double dt = Math.max((now - prevNanos) / 1e9, 0.001);
        prevNanos = now;
        long tsMs = System.currentTimeMillis();

        double cpuPct = clamp(cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0);
        prevTicks = cpu.getSystemCpuLoadTicks();

        VirtualMemory vm = mem.getVirtualMemory();
        long pin = vm.getSwapPagesIn();
        long pout = vm.getSwapPagesOut();
        double pagesIn = Math.max(0, pin - prevPagesIn) / dt;
        double pagesOut = Math.max(0, pout - prevPagesOut) / dt;
        prevPagesIn = pin;
        prevPagesOut = pout;

        double[] d = pollDisks(dt);

        SystemSample system = new SystemSample(tsMs, cpuPct, logical,
                mem.getTotal() / MB, mem.getAvailable() / MB,
                vm.getVirtualInUse() / MB, vm.getVirtualMax() / MB,
                pagesIn, pagesOut, d[0], d[1], d[2], d[3]);

        Map<Integer, OSProcess> current = snapshot();
        List<ProcessSample> processes = new ArrayList<>(current.size());
        for (OSProcess p : current.values()) {
            if (p.getProcessID() == 0) {
                continue; // Idle is the complement of system CPU, it is not a process worth reporting
            }
            OSProcess prev = prevProcs.get(p.getProcessID());
            double cpuProc = 0, rk = 0, wk = 0;
            if (prev != null && prev.getName().equals(p.getName())) {
                cpuProc = clamp(p.getProcessCpuLoadBetweenTicks(prev) * 100.0 / logical);
                rk = Math.max(0, p.getBytesRead() - prev.getBytesRead()) / 1024.0 / dt;
                wk = Math.max(0, p.getBytesWritten() - prev.getBytesWritten()) / 1024.0 / dt;
            }
            processes.add(new ProcessSample(p.getProcessID(), p.getName(), 1,
                    cpuProc, p.getResidentSetSize() / MB, rk, wk));
        }
        prevProcs = current;
        return new Sample(system, processes);
    }

    /** Returns {max busy %, total queue length, total read KB/s, total write KB/s} over all disks. */
    private double[] pollDisks(double dt) {
        double busy = 0, queue = 0, rd = 0, wr = 0;
        for (int i = 0; i < disks.size(); i++) {
            HWDiskStore disk = disks.get(i);
            disk.updateAttributes();
            long transfer = disk.getTransferTime();
            long read = disk.getReadBytes();
            long write = disk.getWriteBytes();
            busy = Math.max(busy, clamp((transfer - prevTransfer[i]) / (dt * 10.0)));
            queue += disk.getCurrentQueueLength();
            rd += Math.max(0, read - prevRead[i]) / 1024.0 / dt;
            wr += Math.max(0, write - prevWrite[i]) / 1024.0 / dt;
            prevTransfer[i] = transfer;
            prevRead[i] = read;
            prevWrite[i] = write;
        }
        return new double[]{busy, queue, rd, wr};
    }

    private Map<Integer, OSProcess> snapshot() {
        Map<Integer, OSProcess> map = new HashMap<>();
        for (OSProcess p : os.getProcesses()) {
            map.put(p.getProcessID(), p);
        }
        return map;
    }

    private static double clamp(double v) {
        return Math.min(100.0, Math.max(0.0, v));
    }
}
