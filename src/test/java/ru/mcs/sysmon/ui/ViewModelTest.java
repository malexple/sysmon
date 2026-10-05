package ru.mcs.sysmon.ui;

import org.junit.jupiter.api.Test;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.model.SystemSample;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewModelTest {

    private static Recording recording(int n) {
        List<SystemSample> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new SystemSample(i * 10_000L, 10, 8, 32000, 16000, 10000, 40000, 0, 0, 0, 0, 0, 0));
        }
        return new Recording(list, new TreeMap<>(), 0);
    }

    @Test
    void updateKeepsTheSelectionAndBumpsTheVersion() {
        ViewModel vm = new ViewModel(recording(10), List.of());
        vm.select(20_000, 50_000);
        AtomicInteger fired = new AtomicInteger();
        vm.addListener(fired::incrementAndGet);

        vm.update(recording(20), List.of());

        assertEquals(1, vm.version);
        assertEquals(1, fired.get());
        assertTrue(vm.hasSelection());
        assertEquals(20_000, vm.from());
        assertEquals(50_000, vm.to());
        assertEquals(190_000, vm.t1);
    }

    @Test
    void selectionOutsideTheNewRangeIsDropped() {
        ViewModel vm = new ViewModel(recording(10), List.of());
        vm.select(60_000, 90_000);
        vm.update(recording(3), List.of());
        assertFalse(vm.hasSelection());
    }

    @Test
    void togglingGapCompressionNotifiesListeners() {
        ViewModel vm = new ViewModel(recording(10), List.of());
        AtomicInteger fired = new AtomicInteger();
        vm.addListener(fired::incrementAndGet);
        vm.setCompressGaps(true);
        vm.setCompressGaps(true);
        assertEquals(1, fired.get());
        assertTrue(vm.scale().compressing());
    }
}
