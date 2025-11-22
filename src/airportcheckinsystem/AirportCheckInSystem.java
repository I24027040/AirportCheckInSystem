/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package airportcheckinsystem;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import javax.swing.*;
import javax.swing.border.*;

public class AirportCheckInSystem extends JFrame {

    /* =========================
     * ===== Domain Model ======
     * ========================= */

    static final class Passenger {
        final String name;
        final String bookingRef;
        Passenger(String name, String bookingRef) {
            this.name = name;
            this.bookingRef = bookingRef;
        }
        @Override public String toString() { return name + "(" + bookingRef + ")"; }
    }

    static final class Seat {
        final String seatId;
        final AtomicReference<String> occupantBookingRef = new AtomicReference<>(null);
        Seat(String seatId) { this.seatId = seatId; }
        boolean tryAssign(String bookingRef) { return occupantBookingRef.compareAndSet(null, bookingRef); }
        boolean isFree() { return occupantBookingRef.get() == null; }
    }

    static final class BaggageRecord {
        final String bagTag;
        final String bookingRef;
        final double weightKg;
        final String kioskId;
        final Instant createdAt = Instant.now();
        BaggageRecord(String bagTag, String bookingRef, double weightKg, String kioskId) {
            this.bagTag = bagTag; this.bookingRef = bookingRef; this.weightKg = weightKg; this.kioskId = kioskId;
        }
    }

    static final class BaggageLedger {
        private final Map<String,BaggageRecord> byBagTag = new ConcurrentHashMap<>();
        private final LongAdder totalBags = new LongAdder();
        private final DoubleAdder totalWeight = new DoubleAdder();
        private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();

        boolean checkInBag(BaggageRecord rec) {
            Lock w = rw.writeLock();
            w.lock();
            try {
                BaggageRecord existing = byBagTag.putIfAbsent(rec.bagTag, rec);
                if (existing == null) {
                    totalBags.increment();
                    totalWeight.add(rec.weightKg);
                    return true;
                }
                return false; // duplicate (idempotent)
            } finally { w.unlock(); }
        }
        int getTotalBags() { return byBagTag.size(); }
        double getTotalWeight() { return totalWeight.sum(); }
    }

    static final class Flight {
        final String flightNo;
        final Map<String, Seat> seats = new ConcurrentHashMap<>();
        final BaggageLedger baggage = new BaggageLedger();

        Flight(String flightNo, int rows, char[] cols) {
            this.flightNo = flightNo;
            for (int r = 1; r <= rows; r++) {
                for (char c : cols) {
                    String id = seatId(r, c);
                    seats.put(id, new Seat(id));
                }
            }
        }

        static String seatId(int row, char col) { return row + String.valueOf(col); }

        Seat assignSeatOrNearest(String preferredSeat, String bookingRef) {
            Seat seat = seats.get(preferredSeat);
            if (seat != null && seat.tryAssign(bookingRef)) return seat;

            // if one seat is found to be free, return the seat num
            for (String cand : nearestCandidates(preferredSeat)) {
                Seat s = seats.get(cand);
                if (s != null && s.tryAssign(bookingRef)) return s;
            }
            return null;
        }

        // returns nearest column and rows
        private List<String> nearestCandidates(String preferred) {
            int i = 0; 
            while (i < preferred.length() && Character.isDigit(preferred.charAt(i))) i++;
            int row = Math.max(1, Integer.parseInt(preferred.substring(0, i)));
            char col = preferred.charAt(i);

            char[] cols = new char[]{'A','B','C','D','E','F'};
            int colIdx = 0; for (int k=0;k<cols.length;k++) if (cols[k]==col) colIdx=k;

            List<String> list = new ArrayList<>();
            int[] offsets = { -1, +1, -2, +2, -3, +3 };
            for (int off : offsets) {
                int idx = colIdx + off;
                if (idx >= 0 && idx < cols.length) list.add(seatId(row, cols[idx]));
            }
            int[] rowOffsets = { -1, +1, -2, +2 };
            for (int ro : rowOffsets) {
                int rr = row + ro; 
                if (rr < 1) continue;
                list.add(seatId(rr, col));
                for (int off : offsets) {
                    int idx = colIdx + off;
                    if (idx >= 0 && idx < cols.length) list.add(seatId(rr, cols[idx]));
                }
            }
            return list;
        }
    }

    // flight number and Flight object
    static final class FlightDatabase {
        final Map<String, Flight> flights = new ConcurrentHashMap<>();
        Flight createFlight(String flightNo, int rows, char[] cols) {
            Flight f = new Flight(flightNo, rows, cols);
            flights.put(flightNo, f);
            return f;
        }
        Flight get(String flightNo) { return flights.get(flightNo); }
    }

    static final class CheckInService {
        private final FlightDatabase db;
        private final Random rnd = new Random();
        CheckInService(FlightDatabase db) { this.db = db; }

        public Seat selectSeat(String flightNo, Passenger p, String preferredSeat, String kioskId) throws Exception {
            return withRetry("SeatSelection", kioskId, () -> {
                simulateNetwork();
                Flight f = db.get(flightNo);
                Seat s = f.assignSeatOrNearest(preferredSeat, p.bookingRef);
                if (s == null) throw new IllegalStateException("No seats available near " + preferredSeat);
                return s;
            });
        }

        public boolean checkInBag(String flightNo, Passenger p, String bagTag, double weightKg, String kioskId) throws Exception {
            return withRetry("Baggage", kioskId, () -> {
                simulateNetwork();
                Flight f = db.get(flightNo);
                return f.baggage.checkInBag(new BaggageRecord(bagTag, p.bookingRef, weightKg, kioskId));
            });
        }

        private void simulateNetwork() throws Exception {
            Thread.sleep(4 + rnd.nextInt(9));
            if (rnd.nextDouble() < 0.06) throw new RuntimeException("Transient I/O failure");
        }

        private <T> T withRetry(String what, String kioskId, Callable<T> op) throws Exception {
            int attempts = 0; long backoff = 12;
            while (true) {
                attempts++;
                try { return op.call(); }
                catch (Exception ex) {
                    if (attempts >= 5) throw ex;
                    long sleep = backoff + ThreadLocalRandom.current().nextLong(0, 10);
                    Thread.sleep(sleep);
                    backoff *= 2;
                }
            }
        }
    }

    /* =========================
     * ========= GUI ===========
     * ========================= */

    // Core
    private final FlightDatabase db = new FlightDatabase();
    private final CheckInService service = new CheckInService(db);
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    // Current Flight
    private final String flightNo = "QZ101";
    private final char[] seatCols = new char[]{'A','B','C','D','E','F'};
    private final int seatRows = 30;

    // UI Widgets
    private JTextField tfName, tfBookingRef, tfPreferredSeat;
    private JTextField tfBagTag, tfBagWeight;
    private JLabel lblTotals, lblBags;
    private JTextArea logArea;
    private JPanel seatGridPanel;
    private final Map<String, JToggleButton> seatButtons = new HashMap<>();

    public AirportCheckInSystem() {
        super("Airport Check-In Kiosk");
        setTitle("Airport Check-In Kiosk – " + flightNo);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1040, 720);
        setLocationRelativeTo(null);

        db.createFlight(flightNo, seatRows, seatCols); // init flight
        buildUI();
        refreshSeatMap();
        refreshTotals();
    }

    private void buildUI() {
        setLayout(new BorderLayout(10,10));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10,10,10,10));

        // Left: Forms
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(buildPassengerPanel());
        left.add(Box.createVerticalStrut(10));
        left.add(buildBaggagePanel());
        left.add(Box.createVerticalStrut(10));
        left.add(buildActionsPanel());
        add(left, BorderLayout.WEST);

        // Right: Seat Map
        seatGridPanel = new JPanel(new GridBagLayout());
        JScrollPane seatScroll = new JScrollPane(seatGridPanel);
        seatScroll.setPreferredSize(new Dimension(650, 520));
        add(seatScroll, BorderLayout.CENTER);

        // Bottom: Totals + Log
        JPanel bottom = new JPanel(new BorderLayout(8,8));
        JPanel totals = new JPanel(new GridLayout(1,2,8,8));
        lblTotals = new JLabel("Seats: 0 / " + (seatRows * seatCols.length));
        lblBags = new JLabel("Bags: 0  |  Weight: 0.00 kg");
        totals.add(lblTotals); totals.add(lblBags);
        bottom.add(totals, BorderLayout.NORTH);

        logArea = new JTextArea(6, 40);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new TitledBorder("Event Log"));
        bottom.add(logScroll, BorderLayout.CENTER);

        add(bottom, BorderLayout.SOUTH);
    }

    private JPanel buildPassengerPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Passenger Check-In"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4,4,4,4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        tfName = new JTextField(16);
        tfBookingRef = new JTextField(12);
        tfPreferredSeat = new JTextField(6);

        int r=0;
        gc.gridx=0; gc.gridy=r; p.add(new JLabel("Name:"), gc);
        gc.gridx=1; p.add(tfName, gc); r++;
        gc.gridx=0; gc.gridy=r; p.add(new JLabel("Booking Ref:"), gc);
        gc.gridx=1; p.add(tfBookingRef, gc); r++;
        gc.gridx=0; gc.gridy=r; p.add(new JLabel("Preferred Seat (e.g., 12C):"), gc);
        gc.gridx=1; p.add(tfPreferredSeat, gc); r++;

        JButton btnAssign = new JButton("Assign Seat");
        btnAssign.addActionListener(e -> onAssignSeat());
        gc.gridx=0; gc.gridy=r; gc.gridwidth=2; p.add(btnAssign, gc);

        return p;
    }

    private JPanel buildBaggagePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Baggage Check-In"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4,4,4,4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        tfBagTag = new JTextField(14);
        tfBagWeight = new JTextField(8);

        int r=0;
        gc.gridx=0; gc.gridy=r; p.add(new JLabel("Bag Tag:"), gc);
        gc.gridx=1; p.add(tfBagTag, gc); r++;
        gc.gridx=0; gc.gridy=r; p.add(new JLabel("Weight (kg):"), gc);
        gc.gridx=1; p.add(tfBagWeight, gc); r++;

        JButton btnBag = new JButton("Check-In Bag");
        btnBag.addActionListener(e -> onCheckInBag());
        gc.gridx=0; gc.gridy=r; gc.gridwidth=2; p.add(btnBag, gc);

        return p;
    }

    private JPanel buildActionsPanel() {
        JPanel p = new JPanel(new GridLayout(1,3,8,8));
        p.setBorder(new TitledBorder("Quick Actions"));
        JButton btnRefresh = new JButton("Refresh Map");
        btnRefresh.addActionListener(e -> refreshSeatMap());

        JButton btnSimSmall = new JButton("Simulate Crowd (50)");
        btnSimSmall.addActionListener(e -> simulateCrowd(50));

        JButton btnClearForm = new JButton("Clear Forms");
        btnClearForm.addActionListener(e -> {
            tfName.setText("");
            tfBookingRef.setText("");
            tfPreferredSeat.setText("");
            tfBagTag.setText("");
            tfBagWeight.setText("");
        });

        p.add(btnRefresh); p.add(btnSimSmall); p.add(btnClearForm);
        return p;
    }

    /* =========================
     * ==== Event Handlers =====
     * ========================= */

    private void onAssignSeat() {
        final String name = tfName.getText().trim();
        final String br = tfBookingRef.getText().trim();
        final String pref = tfPreferredSeat.getText().trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty() || br.isEmpty() || pref.isEmpty()) {
            log("Passenger Check In Details contain missing fields.");
            toast("Please fill Name, Booking Ref, and Preferred Seat.");
            return;
        }
        final Passenger p = new Passenger(name, br);

        disableForms(true);
        pool.submit(() -> {
            try {
                Seat s = service.selectSeat(flightNo, p, pref, "GUI");
                log("Seat assigned: " + p + " -> " + s.seatId);
                SwingUtilities.invokeLater(() -> {
                    highlightSeat(s.seatId, true);
                    refreshTotals();
                });
            } catch (Exception ex) {
                log("Seat assignment FAILED: " + ex.getMessage());
                toastLater("Seat assignment failed: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> disableForms(false));
            }
        });
    }

    private void onCheckInBag() {
        final String br = tfBookingRef.getText().trim();
        final String tag = tfBagTag.getText().trim();
        final String weightStr = tfBagWeight.getText().trim();

        if (br.isEmpty() || tag.isEmpty() || weightStr.isEmpty()) {
            log("Baggage Check In Details contain missing field(s)");
            toast("Please fill Booking Ref, Bag Tag, and Weight.");
            return;
        }
        double w;
        try { w = Double.parseDouble(weightStr); }
        catch (NumberFormatException nfe) { 
            log("Non numeric weight inserted");
            toast("Weight must be a number."); return; 
        }

        final Passenger p = new Passenger("N/A", br);

        disableForms(true);
        pool.submit(() -> {
            try {
                boolean ok = service.checkInBag(flightNo, p, tag, w, "GUI");
                if (ok) {
                    log("Bag accepted: " + tag + " (" + w + " kg) for " + br);
                } else {
                    log("Duplicate bagTag ignored: " + tag + " for " + br);
                    throw new IllegalStateException(tag +" is a duplicate tag, please try again");
                }
                SwingUtilities.invokeLater(this::refreshTotals);
            } catch (Exception ex) {
                log("Baggage check-in FAILED: " + ex.getMessage());
                toastLater("Baggage check-in failed: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> disableForms(false));
            }
        });
    }

    private void simulateCrowd(int n) {
        // fire off N pseudo-random passengers concurrently
        String[] hotSeats = {"12C","12D","13C","14D","10A","10F","15C"};
        Random rnd = new Random();
        for (int i=1;i<=n;i++) {
            final String name = "PAX"+UUID.randomUUID().toString().substring(0,6);
            final String br = "BR"+(100000 + rnd.nextInt(900000));
            final String pref = hotSeats[rnd.nextInt(hotSeats.length)];
            final Passenger p = new Passenger(name, br);
            pool.submit(() -> {
                try {
                    Seat s = service.selectSeat(flightNo, p, pref, "SIM");
                    SwingUtilities.invokeLater(() -> {
                        highlightSeat(s.seatId, true);
                        refreshTotals();
                    });
                    // Randomly 0-2 bags
                    int bags = rnd.nextInt(3);
                    for (int b=1;b<=bags;b++) {
                        String tag = br + "-B" + b;
                        double w = 12 + rnd.nextDouble()*16;
                        service.checkInBag(flightNo, p, tag, w, "SIM");
                        SwingUtilities.invokeLater(this::refreshTotals);
                    }
                } catch (Exception ex) {
                    // seats full -> ignore
                }
            });
        }
        log("Simulation started: " + n + " concurrent passengers.");
    }

    /* =========================
     * ==== Seat Map (UI) ======
     * ========================= */

    private void refreshSeatMap() {
        seatGridPanel.removeAll();
        seatButtons.clear();

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2,2,2,2);

        // Header row
        gc.gridx = 0; gc.gridy = 0;
        seatGridPanel.add(new JLabel(" "), gc);
        for (int c=0;c<seatCols.length;c++) {
            gc.gridx = c+1;
            JLabel head = new JLabel(String.valueOf(seatCols[c]), SwingConstants.CENTER);
            head.setFont(head.getFont().deriveFont(Font.BOLD));
            head.setPreferredSize(new Dimension(48, 22));
            seatGridPanel.add(head, gc);
        }

        Flight f = db.get(flightNo);
        for (int r=1;r<=seatRows;r++) {
            gc.gridy = r;
            // Row label
            gc.gridx = 0;
            JLabel rowL = new JLabel(String.format("%02d", r), SwingConstants.CENTER);
            rowL.setPreferredSize(new Dimension(48, 28));
            seatGridPanel.add(rowL, gc);

            for (int c=0;c<seatCols.length;c++) {
                gc.gridx = c+1;
                String id = Flight.seatId(r, seatCols[c]);
                JToggleButton btn = new JToggleButton(id);
                btn.setEnabled(false);
                btn.setMargin(new Insets(2,4,2,4));
                btn.setPreferredSize(new Dimension(64, 28));
                seatButtons.put(id, btn);
                seatGridPanel.add(btn, gc);

                // Initial color
                boolean occupied = !f.seats.get(id).isFree();
                colorSeat(btn, occupied);
            }
        }
        seatGridPanel.revalidate();
        seatGridPanel.repaint();
    }

    private void highlightSeat(String seatId, boolean occupied) {
        JToggleButton btn = seatButtons.get(seatId);
        if (btn != null) {
            colorSeat(btn, occupied);
        }
    }

    private void colorSeat(AbstractButton btn, boolean occupied) {
        btn.setSelected(occupied);
        if (occupied) {
            btn.setBackground(new Color(0xFFCC99));
            btn.setForeground(Color.BLACK);
        } else {
            btn.setBackground(new Color(0xCCFFCC));
            btn.setForeground(Color.BLACK);
        }
        btn.setOpaque(true);
        btn.setBorderPainted(true);
    }

    /* =========================
     * ===== Totals & Log ======
     * ========================= */

    private void refreshTotals() {
        Flight f = db.get(flightNo);
        int total = seatRows * seatCols.length;
        int filled = 0;
        for (Seat s : f.seats.values()) if (!s.isFree()) filled++;
        lblTotals.setText("Seats: " + filled + " / " + total);

        lblBags.setText(String.format(Locale.US,
                "Bags: %d  |  Weight: %.2f kg",
                f.baggage.getTotalBags(), f.baggage.getTotalWeight()));
    }

    private void log(String msg) {
        String ts = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalTime.now());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + ts + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void toast(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Info", JOptionPane.INFORMATION_MESSAGE);
    }
    private void toastLater(String msg) {
        SwingUtilities.invokeLater(() -> toast(msg));
    }

    private void disableForms(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        for (Component c : getContentPane().getComponents()) c.setEnabled(!busy);
        // Re-enable scrollpane / content wrapper properly:
        getContentPane().setEnabled(true);
    }

    /* =========================
     * ========= Main ==========
     * ========================= */

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AirportCheckInSystem ui = new AirportCheckInSystem();
            ui.setVisible(true);
        });
    }
}
