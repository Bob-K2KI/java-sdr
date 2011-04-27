// Swing based display framework for SDR, just a frame with some tabs to select
// display format, and the FCD input config/thread.
import javax.swing.SwingUtilities;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JSplitPane;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.AbstractAction;
import javax.swing.event.ChangeListener;
import javax.swing.event.ChangeEvent;
import java.awt.Graphics;
import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

// FUNcube Dongle API
import uk.org.funcube.fcdapi.FCD;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import java.util.Properties;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class jsdr implements Runnable {

	public static Properties config;
	public final String CFG_TITLE = "title";
	public final String CFG_WIDTH = "width";
	public final String CFG_HEIGHT= "height";
	public final String CFG_AUDDEV= "audio-device";
	public final String CFG_AUDRAT= "audio-rate";
	public final String CFG_AUDBIT= "audio-bits";
	public final String CFG_AUDMOD= "audio-mode";
	public final String CFG_ICORR = "i-correction";
	public final String CFG_QCORR = "q-correction";
	public final String CFG_FREQ  = "frequency";

	protected JFrame frame;
	protected JLabel status;
	protected JTabbedPane tabs;
	protected int ic, qc;
	private AudioFormat format;
	private int bufsize;
	private FCD fcd;
	private int freq;
	private boolean done;

	public static String getConfig(String prop, String def) {
		String val = config.getProperty(prop, def);
		config.setProperty(prop, val);
		return val;
	}

	public static int getIntConfig(String prop, int def) {
		try {
			String val = config.getProperty(prop);
			if (val!=null)
				return Integer.parseInt(val);
			else
				config.setProperty(prop, String.valueOf(def));
		} catch (Exception e) {
		}
		return def;
	}

	private jsdr() {
		// The audio format
		int rate = getIntConfig(CFG_AUDRAT, 96000);	// Default 96kHz sample rate
		int bits = getIntConfig(CFG_AUDBIT, 16);		// Default 16 bits/sample
		int chan = getConfig(CFG_AUDMOD, "IQ").equals("IQ") ? 2 : 1; // IQ mode => 2 channels
		int size = (bits+7)/8 * chan;							// Round up bits to bytes..
		format = new AudioFormat(
			AudioFormat.Encoding.PCM_SIGNED,	// We always expect signed PCM
			rate, bits, chan, size, rate,
			false	// We always expect little endian samples
		);
		// Choose a buffer size that gives us ~10Hz refresh rate
		bufsize = rate*size/10;

		// The main frame
		frame = new JFrame(getConfig(CFG_TITLE, "Java SDR v0.1"));
		frame.setSize(getIntConfig(CFG_WIDTH, 800), getIntConfig(CFG_HEIGHT, 600));
		frame.setResizable(true);
		// The top-bottom split
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		frame.add(split);
		split.setResizeWeight(0.75);
		split.setDividerSize(3);
		// The tabbed display panes (in top)
		tabs = new JTabbedPane(JTabbedPane.BOTTOM);
		split.setTopComponent(tabs);
		// The content in each tab: TODO display classes..
		tabs.add("Spectrum", new fft(this, format, bufsize));
		tabs.add("Phase", new phase(this, format, bufsize));
		// The control area (in bottom)
		JPanel controls = new JPanel();
		split.setBottomComponent(controls);
		// control layout
		controls.setLayout(new BorderLayout());
		// keyboard hotkeys
		AbstractAction act = new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				char c = e.getActionCommand().charAt(0);
				int f = -1;
				if ('i'==c)
					ic+=1;
				else if('I'==c)
					ic-=1;
				else if('q'==c)
					qc+=1;
				else if('Q'==c)
					qc-=1;
				else if('f'==c || 'F'==c) {
					f = freqDialog();
				} else if('u'==c) {
					f = freq+1;
				} else if ('U'==c) {
					f = freq+10;
				} else if('d'==c) {
					f = freq-1;
				} else if ('D'==c) {
					f = freq-10;
				} else if ('s'==c) {
					f = freq+50;
				} else if ('S'==c) {
					f = freq-50;
				}
				if (f>=50000) {
					freq = f;
					fcdSetFreq(f);
				}
				saveConfig();
			}
		};
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('f'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('i'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('q'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('I'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('Q'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('u'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('d'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('U'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('D'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('s'), "Key");
		frame.getLayeredPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
			KeyStroke.getKeyStroke('S'), "Key");
		frame.getLayeredPane().getActionMap().put("Key", act);

		// status bar
		status = new JLabel(frame.getTitle());
		controls.add(status, BorderLayout.SOUTH);
		// Close handler
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});
		// Done - show it!
		frame.setVisible(true);
		// Start audio thread
		done = false;
		ic = getIntConfig(CFG_ICORR, 0);
		qc = getIntConfig(CFG_QCORR, 0);
		new Thread(this).start();
	}

	private int freqDialog() {
		if (fcd!=null) {
			try {
				String tune = JOptionPane.showInputDialog(frame, "Please enter new frequency");
				return Integer.parseInt(tune);
			} catch (Exception e) {
				status.setText("Invalid frequency");
			}
		} else {
			status.setText("Not an FCD, unable to tune");
		}
		return -1;
	}

	private void fcdSetFreq(int f) {
		freq = f;
		if (fcd.FME_APP!=fcd.fcdAppSetFreqkHz(freq))
			status.setText("unable to tune FCD");
		else
			status.setText("FCD tuned to "+freq+" kHz");
	}

	// Audio input thread..
	public void run() {
		// Open the appropriate device..
		String dev = config.getProperty(CFG_AUDDEV, "FUNcube Dongle");	// Default to FCD
		if (dev.equals("FUNcube Dongle")) {
			// FCD in use, we can tune it ourselves..
			fcd = new FCD();
			while (fcd.FME_APP!=fcd.fcdGetMode()) {
				status.setText("FCD not present or not in app mode, resetting..");
				fcd.fcdBlReset();
				try {
					Thread.sleep(1000);
				} catch(Exception e) {}
			}
			freq = getIntConfig(CFG_FREQ, 100000);
			fcdSetFreq(freq);
		}
		TargetDataLine line = null;
		Mixer.Info[] mixers = AudioSystem.getMixerInfo();
		int m;
		for (m=0; m<mixers.length; m++) {
			// NB: Linux puts the device name in description field, Windows in name field.. sheesh.
			if (mixers[m].getDescription().indexOf(dev)>=0 ||
			    mixers[m].getName().indexOf(dev)>=0) {
				// Found mixer/device, try and get a capture line in specified format
				try {
					line = (TargetDataLine) AudioSystem.getTargetDataLine(format, mixers[m]);
				} catch (Exception e) {
					status.setText("unable to open audio device: "+dev);
				}
				break;
			}
		}
		if (line!=null) {
			try {
				// Use a buffer large enough to produce ~10Hz refresh rate.
				byte[] tmp = new byte[bufsize];
				ByteBuffer buf = ByteBuffer.allocate(bufsize);
				buf.order(format.isBigEndian() ?
					ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
				line.open(format, bufsize);
				line.start();
				while (!done) {
					buf.clear();
					int n = line.read(tmp, 0, tmp.length);
					buf.put(tmp);
					for (int t=0; t<tabs.getTabCount(); t++) {
						Object o = tabs.getComponentAt(t);
						if (o instanceof JsdrTab) {
							buf.rewind();
							((JsdrTab)o).newBuffer(buf);
						}
					}
				}
				status.setText("Audio input done");
			} catch (Exception e) {
				status.setText("Audio oops: "+e);
				e.printStackTrace();
			}
		} else {
			status.setText("Unable to open audio");
		}
	}

	private void saveConfig() {
		try {
			FileOutputStream cfo = new FileOutputStream("jsdr.properties");
			config.setProperty(CFG_ICORR, String.valueOf(ic));
			config.setProperty(CFG_QCORR, String.valueOf(qc));
			config.setProperty(CFG_FREQ, String.valueOf(freq));
			config.store(cfo, "Java SDR V0.1");
			cfo.close();
		} catch (Exception e) {
			status.setText("Save oops: "+e);
		}
	}

	public static void main(String[] args) {
		// Load config..
		config = new Properties();
		try {
			FileInputStream cfi = new FileInputStream("jsdr.properties");
			config.load(cfi);
			cfi.close();
		} catch (Exception e) {
			System.err.println("unable to load config, using defaults");
		}
		// Get the UI up as soon as possible, we might need to display errors..
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				jsdr me = new jsdr();
			}
		});
	}

	// Interface implemented by tabbed display components
	public interface JsdrTab {
		public void newBuffer(ByteBuffer buf);
	}
}
