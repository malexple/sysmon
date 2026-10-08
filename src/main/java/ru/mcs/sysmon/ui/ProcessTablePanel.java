package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.analysis.ProcessAggregator;
import ru.mcs.sysmon.analysis.ProcessAggregator.Stats;
import ru.mcs.sysmon.cli.Lang;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-process table (grouped by name) for the selected interval; cells are shaded by their share of the
 * column maximum. Read and write speeds are separate columns, in MB/s.
 */
final class ProcessTablePanel extends JPanel {

    private static final int COLUMNS = 7;

    private final ViewModel vm;
    private final Palette pal;
    private final StatsModel model;
    private final JTable table;

    ProcessTablePanel(ViewModel vm, Palette pal, Lang lang) {
        super(new BorderLayout());
        this.vm = vm;
        this.pal = pal;
        this.model = new StatsModel(new String[]{
                lang.t("Process", "Процесс"), "CPU avg %", "CPU max %", "RSS avg MB",
                lang.t("RSS swing MB", "RSS размах МБ"),
                lang.t("Read MB/s", "Чтение МБ/с"), lang.t("Write MB/s", "Запись МБ/с")});
        this.table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.round(table.getRowHeight() * 1.1f));
        table.getTableHeader().putClientProperty("FlatLaf.style", "cellMargins: 2,12,2,12");
        HeatRenderer renderer = new HeatRenderer();
        for (int c = 0; c < model.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(renderer);
        }
        table.getColumnModel().getColumn(0).setPreferredWidth(220);

        JLabel title = new JLabel(lang.t("Processes by name in the selected interval", "Процессы по имени в выбранном интервале"));
        title.setBorder(new EmptyBorder(6, 10, 6, 10));
        add(title, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        refresh();
        ((TableRowSorter<?>) table.getRowSorter()).setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
        vm.addListener(this::refresh);
    }

    private void refresh() {
        model.set(ProcessAggregator.aggregate(vm.recording.processes(), vm.from(), vm.to()));
    }

    private static final class StatsModel extends AbstractTableModel {
        private final String[] columns;
        private List<Stats> rows = new ArrayList<>();
        private final double[] columnMax = new double[COLUMNS];

        StatsModel(String[] columns) {
            this.columns = columns;
        }

        void set(List<Stats> stats) {
            rows = new ArrayList<>(stats);
            for (int c = 1; c < columns.length; c++) {
                double max = 0;
                for (Stats s : rows) {
                    max = Math.max(max, number(s, c));
                }
                columnMax[c] = max;
            }
            fireTableDataChanged();
        }

        double max(int column) {
            return columnMax[column];
        }

        private static double number(Stats s, int c) {
            return switch (c) {
                case 1 -> s.cpuAvg();
                case 2 -> s.cpuMax();
                case 3 -> s.rssAvgMb();
                case 4 -> s.rssSwingMb();
                case 5 -> s.readAvgKbps() / 1024.0;
                default -> s.writeAvgKbps() / 1024.0;
            };
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? String.class : Double.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Stats s = rows.get(row);
            return column == 0 ? s.name() : (Object) number(s, column);
        }
    }

    private final class HeatRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            setBackground(null);
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            int mc = t.convertColumnIndexToModel(column);
            if (mc == 0) {
                setHorizontalAlignment(LEFT);
                return this;
            }
            double v = ((Number) value).doubleValue();
            setHorizontalAlignment(RIGHT);
            setText(String.format(Locale.ROOT, mc <= 2 ? "%.1f" : mc <= 4 ? "%.0f" : "%.2f", v));
            if (!selected) {
                double max = model.max(mc);
                setBackground(Palette.blend(t.getBackground(), pal.accent, max > 0 ? 0.5 * v / max : 0));
            }
            return this;
        }
    }
}
