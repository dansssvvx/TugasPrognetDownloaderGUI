import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutionException;

/**
 * Tugas Pemrograman Internet - Downloader dengan GUI (Java Swing)
 *
 * Stream yang digunakan:
 *  - URL / HttpURLConnection        : koneksi HTTP ke server
 *  - InputStream (getInputStream)   : aliran byte dari jaringan
 *  - BufferedInputStream            : buffering pembacaan dari jaringan
 *  - FileOutputStream               : aliran byte ke file di disk
 *  - BufferedOutputStream           : buffering penulisan ke disk
 *
 * Fitur: progress bar, kecepatan, ETA, redirect, resume (HTTP Range),
 *        cancel, nama file otomatis, log aktivitas.
 */
public class DownloaderGUI extends JFrame {

    private final JTextField tfUrl  = new JTextField(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/640px-PNG_transparency_demonstration_1.png");
    private final JTextField tfFile = new JTextField(System.getProperty("user.home") + File.separator + "unduhan.png");
    private final JButton btnBrowse = new JButton("Pilih...");
    private final JButton btnStart  = new JButton("Download");
    private final JButton btnCancel = new JButton("Batal");
    private final JCheckBox cbResume = new JCheckBox("Lanjutkan jika file sebagian sudah ada (resume)", true);
    private final JProgressBar bar  = new JProgressBar(0, 100);
    private final JLabel lblStatus  = new JLabel("Siap.");
    private final JTextArea log     = new JTextArea(9, 40);

    private DownloadWorker worker;

    public DownloaderGUI() {
        super("Downloader - Pemrograman Internet");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0; form.add(new JLabel("URL"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2; form.add(tfUrl, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.gridwidth = 1; form.add(new JLabel("Simpan ke"), c);
        c.gridx = 1; c.weightx = 1; form.add(tfFile, c);
        c.gridx = 2; c.weightx = 0; form.add(btnBrowse, c);

        c.gridx = 1; c.gridy = 2; c.gridwidth = 2; form.add(cbResume, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(btnStart);
        buttons.add(btnCancel);
        btnCancel.setEnabled(false);
        c.gridy = 3; form.add(buttons, c);

        bar.setStringPainted(true);
        c.gridy = 4; form.add(bar, c);
        c.gridy = 5; form.add(lblStatus, c);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(log);
        sp.setBorder(BorderFactory.createTitledBorder("Log"));
        sp.setPreferredSize(new Dimension(640, 180));

        add(form, BorderLayout.NORTH);
        add(sp, BorderLayout.CENTER);

        btnBrowse.addActionListener(e -> pilihFile());
        btnStart.addActionListener(e -> mulai());
        btnCancel.addActionListener(e -> { if (worker != null) worker.cancel(true); });

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
    }

    private void pilihFile() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(tfFile.getText()));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            tfFile.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void mulai() {
        String url = tfUrl.getText().trim();
        String path = tfFile.getText().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            JOptionPane.showMessageDialog(this, "URL harus diawali http:// atau https://",
                    "URL tidak valid", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Jika nama file kosong / hanya folder, ambil nama dari URL
        File target = new File(path);
        if (path.isEmpty() || target.isDirectory()) {
            String nama = url.substring(url.lastIndexOf('/') + 1);
            int q = nama.indexOf('?');
            if (q >= 0) nama = nama.substring(0, q);
            if (nama.isEmpty()) nama = "unduhan.bin";
            target = new File(path.isEmpty() ? System.getProperty("user.home") : path, nama);
            tfFile.setText(target.getAbsolutePath());
        }

        log.setText("");
        bar.setValue(0);
        setBusy(true);
        worker = new DownloadWorker(url, target, cbResume.isSelected());
        worker.addPropertyChangeListener(ev -> {
            if ("progress".equals(ev.getPropertyName())) bar.setValue((Integer) ev.getNewValue());
        });
        worker.execute();
    }

    private void setBusy(boolean busy) {
        btnStart.setEnabled(!busy);
        btnCancel.setEnabled(busy);
        tfUrl.setEnabled(!busy);
        tfFile.setEnabled(!busy);
        btnBrowse.setEnabled(!busy);
    }

    private void tulisLog(String s) {
        log.append(s + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    /** Pekerja latar belakang agar GUI tidak membeku saat mengunduh. */
    private class DownloadWorker extends SwingWorker<Long, String> {
        private final String urlStr;
        private final File target;
        private final boolean resume;

        DownloadWorker(String urlStr, File target, boolean resume) {
            this.urlStr = urlStr;
            this.target = target;
            this.resume = resume;
        }

        /** Buka koneksi dan ikuti redirect (maks 10 kali). */
        private HttpURLConnection buka(String alamat, long mulaiByte) throws IOException {
            for (int i = 0; i < 10; i++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(alamat).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (JavaDownloader/1.0)");
                if (mulaiByte > 0) conn.setRequestProperty("Range", "bytes=" + mulaiByte + "-");

                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) throw new IOException("Redirect tanpa header Location");
                    alamat = new URL(new URL(alamat), loc).toString();
                    publish("Redirect ke: " + alamat);
                    continue;
                }
                return conn;
            }
            throw new IOException("Terlalu banyak redirect");
        }

        @Override
        protected Long doInBackground() throws Exception {
            long sudahAda = (resume && target.exists()) ? target.length() : 0;
            publish("Menghubungi server...");
            HttpURLConnection conn = buka(urlStr, sudahAda);
            int code = conn.getResponseCode();

            boolean append = false;
            if (code == 206) {
                append = true;
                publish("Server mendukung resume, lanjut dari byte " + sudahAda);
            } else if (code == 200) {
                sudahAda = 0; // unduh dari awal
            } else if (code == 416) {
                publish("File sudah lengkap, tidak ada yang perlu diunduh.");
                conn.disconnect();
                setProgress(100);
                return sudahAda;
            } else {
                conn.disconnect();
                throw new IOException("Server mengembalikan kode HTTP " + code);
            }

            long sisa = conn.getContentLengthLong();
            long total = sisa > 0 ? sisa + sudahAda : -1;
            publish("Tipe    : " + conn.getContentType());
            publish("Ukuran  : " + (total > 0 ? String.format("%,d bytes (%.2f KB)", total, total / 1024.0) : "tidak diketahui"));
            publish("Tujuan  : " + target.getAbsolutePath());

            long terbaca = sudahAda;
            long mulaiWaktu = System.nanoTime();
            long updateTerakhir = 0;
            long byteSesi = 0;

            // Rantai stream: jaringan -> BufferedInputStream -> buffer -> BufferedOutputStream -> file
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 16 * 1024);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target, append), 16 * 1024)) {

                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (isCancelled()) {
                        publish("Dibatalkan oleh pengguna.");
                        return terbaca;
                    }
                    out.write(buffer, 0, n);
                    terbaca += n;
                    byteSesi += n;

                    long now = System.nanoTime();
                    if (now - updateTerakhir > 150_000_000L) { // update UI tiap ~150 ms
                        updateTerakhir = now;
                        double detik = (now - mulaiWaktu) / 1e9;
                        double kbps = detik > 0 ? (byteSesi / 1024.0) / detik : 0;
                        String status;
                        if (total > 0) {
                            int persen = (int) (terbaca * 100 / total);
                            setProgress(Math.min(persen, 100));
                            long sisaByte = total - terbaca;
                            long eta = kbps > 0 ? (long) (sisaByte / 1024.0 / kbps) : 0;
                            status = String.format("%,d / %,d bytes  |  %.1f KB/s  |  sisa ~%d detik",
                                    terbaca, total, kbps, eta);
                        } else {
                            status = String.format("%,d bytes  |  %.1f KB/s", terbaca, kbps);
                        }
                        SwingUtilities.invokeLater(() -> lblStatus.setText(status));
                    }
                }
                out.flush();
            } finally {
                conn.disconnect();
            }

            setProgress(100);
            double durasi = (System.nanoTime() - mulaiWaktu) / 1e9;
            publish(String.format("Selesai! %,d bytes dalam %.2f detik", terbaca, durasi));
            return terbaca;
        }

        @Override
        protected void process(java.util.List<String> chunks) {
            for (String s : chunks) tulisLog(s);
        }

        @Override
        protected void done() {
            setBusy(false);
            if (isCancelled()) {
                lblStatus.setText("Dibatalkan.");
                return;
            }
            try {
                get();
                lblStatus.setText("Selesai.");
            } catch (ExecutionException ex) {
                lblStatus.setText("Gagal.");
                tulisLog("ERROR: " + ex.getCause());
                JOptionPane.showMessageDialog(DownloaderGUI.this, ex.getCause().getMessage(),
                        "Download gagal", JOptionPane.ERROR_MESSAGE);
            } catch (InterruptedException ignored) { }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            new DownloaderGUI().setVisible(true);
        });
    }
}
