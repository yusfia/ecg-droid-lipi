package com.ilham1012.ecgbpi.model;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.bitalino.comm.BITalinoFrame;

import com.ilham1012.ecgbpi.app.Constants;
import com.ilham1012.ecgbpi.R;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.StatFs;
import android.util.JsonWriter;
import android.util.Log;


/**
 * Saves and compresses recording data into android' external file system
 * @author Carlos Marten
 */
public class DataManager {

    // Standard debug constant
    private static final String TAG = DataManager.class.getName();

    public static final int MSG_PERCENTAGE = 99;

    public static final int STATE_APPENDING_HEADER = 1;
    public static final int STATE_COMPRESSING_FILE = 2;

    private Messenger client = null;

    private DeviceConfiguration configuration;
    private OutputStreamWriter outStreamWriter;
    private BufferedWriter bufferedWriter;
    private int BUFFER = 524288; // 0.5MB Optimal for Android devices
    private int numberOfChannelsActivated;

    private String recordingName;
    private String duration;


    private Context context;

    /**
     * Constructor. Initializes the number of channels activated, the outStream
     * write and the Buffered writer
     */
    public DataManager(Context serviceContext, String _recordingName, DeviceConfiguration _configuration) {
        this.context = serviceContext;
        this.recordingName = _recordingName;
        this.configuration = _configuration;

        this.numberOfChannelsActivated = configuration.getActiveChannelsNumber();
        try {
            outStreamWriter = new OutputStreamWriter(context.openFileOutput(Constants.TEMP_FILE, Context.MODE_PRIVATE));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "file to write frames on, not found", e);
        }
        bufferedWriter = new BufferedWriter(outStreamWriter);
    }

    /**
     * Writes a frame (row) on text file that will go after the header. Returns
     * true if wrote successfully and false otherwise.
     */
    private final StringBuilder sb = new StringBuilder(400);
    public boolean writeFrameToTmpFile(BITalinoFrame frame, int frameSeq) {
        sb.delete(0, sb.length());
        try {
            sb.append(frameSeq).append("\t");
            // WRITE THE DATA OF ACTIVE CHANNELS ONLY

            //Bitalino always send 6 channels but only active which you selected
            ArrayList<Integer> activeChannels = configuration.getActiveChannels();
            int[] activeChannelsArray = convertToBitalinoChannelsArray(activeChannels);
            int firstChannelUsed=activeChannelsArray[0];

            for(int i=0; i< activeChannelsArray.length;i++){
                sb.append(frame.getAnalog(activeChannelsArray[i])).append("\t");
            }
            // WRITE A NEW LINE
            bufferedWriter.write(sb.append("\n").toString());

        } catch (Exception e) {
            try {bufferedWriter.close();} catch (Exception e1) {}
            Log.e(TAG, "Exception while writing frame row", e);
            return false;
        }
        return true;
    }

    private int[] convertToBitalinoChannelsArray(
            ArrayList<Integer> activeChannels) {
        int[] activeChannelsArray = new int[activeChannels.size()];
        Iterator<Integer> iterator = activeChannels.iterator();
        Log.e(TAG, "BITALINO ActiveChannels ");

        for (int i = 0; i < activeChannelsArray.length; i++) {
            activeChannelsArray[i] = iterator.next().intValue()-1;
            Log.e(TAG, "BITALINO ActiveChannels C" + activeChannelsArray[i]);
        }

        return activeChannelsArray;
    }

    /**
     * Creates and appends the header on the recording session file
     *
     * Returns true if the text file was written successfully or false if an
     * exception was caught
     */
    private boolean appendHeaderOld() {

        DateFormat dateFormat = DateFormat.getDateTimeInstance();
        String tmpFilePath = context.getFilesDir() + "/" + Constants.TEMP_FILE;
        Date date = new Date();
        OutputStreamWriter out = null;
        BufferedInputStream origin = null;
        BufferedOutputStream dest = null;
        FileInputStream fi = null;

        try {
            out = new OutputStreamWriter(context.openFileOutput(recordingName + ".txt", Context.MODE_PRIVATE));
            out.write(String.format("%-10s %-10s%n",   "# " + context.getString(R.string.bs_header_name), configuration.getName()));
            out.write(String.format("%-10s %-14s%n",   "# " + context.getString(R.string.bs_header_date), dateFormat.format(date)));
            out.write(String.format("%-10s %-4s%n",    "# " + context.getString(R.string.bs_header_frequency), configuration.getVisualizationFrequency() + " Hz"));
            out.write(String.format("%-10s %-10s%n",   "# " + context.getString(R.string.bs_header_bits), configuration.getNumberOfBits() + " bits"));
            out.write(String.format("%-10s %-14s%n",   "# " + context.getString(R.string.bs_header_duration), duration + " " + context.getString(R.string.bs_header_seconds)));
            out.write(String.format("%-10s %-14s%n%n", "# " + context.getString(R.string.bs_header_active_channels), configuration.getActiveChannels().toString()));
            out.write("#num ");

            for(int i: configuration.getActiveChannels()){
                out.write("ch " + i + " ");
            }

            out.write("\n");
            out.flush();
            out.close();

            // APPEND DATA
            FileOutputStream outBytes = new FileOutputStream(context.getFilesDir()
                    + "/" + recordingName + Constants.TEXT_FILE_EXTENTION, true);
            dest = new BufferedOutputStream(outBytes);
            fi = new FileInputStream(tmpFilePath);

            origin = new BufferedInputStream(fi, BUFFER);
            int count;
            byte data[] = new byte[BUFFER];

            Long tmpFileSize = (new File(tmpFilePath)).length();
            long currentBitsCopied = 0;

            while ((count = origin.read(data, 0, BUFFER)) != -1) {
                dest.write(data, 0, count);
                currentBitsCopied += BUFFER;
                sendPercentageToActivity((int)( currentBitsCopied * 100 / tmpFileSize), STATE_APPENDING_HEADER);
            }

        } catch (FileNotFoundException e) {
            Log.e(TAG, "File to write header on, not found", e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Write header stream exception", e);
            return false;
        }
        finally{
            try {
                fi.close();
                out.close();
                origin.close();
                dest.close();
                context.deleteFile(Constants.TEMP_FILE);
            } catch (IOException e) {
                try {out.close();} catch (IOException e1) {}
                try {origin.close();} catch (IOException e1) {}
                try {dest.close();} catch (IOException e1) {};
                Log.e(TAG, "Closing streams exception", e);
                return false;
            }
        }
        return true;
    }

    /**
     * New header makes to work with BioSignals
     * Creates and appends the header on the recording session file
     *
     * Returns true if the text file was written successfully or false if an
     * exception was caught
     */
    private boolean appendHeader() {

        DateFormat dateFormat = DateFormat.getDateTimeInstance();
        String tmpFilePath = context.getFilesDir() + "/" + Constants.TEMP_FILE;
        Date date = new Date();
        OutputStreamWriter out = null;
        BufferedInputStream origin = null;
        BufferedOutputStream dest = null;
        FileInputStream fi = null;

        try {
            out = new OutputStreamWriter(context.openFileOutput(recordingName + ".txt", Context.MODE_PRIVATE));
            out.write("# JSON Text File Format\n");


            out.write("# {");

            out.write("\"SamplingResolution\": ");
            out.write("\"10\", ");
            out.write("\"SampledChannels\": ");

            int numChannels=configuration.getActiveChannels().size()+2;
            out.write("[");
            for(int i=1;i<numChannels;i++){
                out.write(i+", ");
            }
            out.write(numChannels+"");
            out.write("], ");
            out.write("\"SamplingFrequency\": ");
            out.write("\""+configuration.getSamplingFrequency()+"\", ");
            out.write("\"ColumnLabels\": ");
            out.write("[");
            out.write("\"signals/others/SeqN\", ");
            out.write("\"signals/others/Ind\"");
            for(int i: configuration.getActiveChannels()){
                out.write(", \"signals/AnalogInputs/Analog"+i+"/Signal"+i+"\"");
            }
            out.write("], ");

            out.write("\"AcquiringDevice\": ");
            out.write("\""+configuration.getMacAddress()+"\", ");
            out.write("\"Version\": ");
            out.write("\""+"111"+"\", ");
            out.write("\"StartDateTime\": ");
            out.write("\""+dateFormat.format(date)+"\"");
            out.write("}");
            out.write("\n");
            out.write("# EndOfHeader\n");

            out.flush();
            out.close();

            // APPEND DATA
            FileOutputStream outBytes = new FileOutputStream(context.getFilesDir()
                    + "/" + recordingName + Constants.TEXT_FILE_EXTENTION, true);
            dest = new BufferedOutputStream(outBytes);
            fi = new FileInputStream(tmpFilePath);

            origin = new BufferedInputStream(fi, BUFFER);
            int count;
            byte data[] = new byte[BUFFER];

            Long tmpFileSize = (new File(tmpFilePath)).length();
            long currentBitsCopied = 0;

            while ((count = origin.read(data, 0, BUFFER)) != -1) {
                dest.write(data, 0, count);
                currentBitsCopied += BUFFER;
                sendPercentageToActivity((int)( currentBitsCopied * 100 / tmpFileSize), STATE_APPENDING_HEADER);
            }

        } catch (FileNotFoundException e) {
            Log.e(TAG, "File to write header on, not found", e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Write header stream exception", e);
            return false;
        }
        finally{
            try {
                fi.close();
                out.close();
                origin.close();
                dest.close();
                context.deleteFile(Constants.TEMP_FILE);
            } catch (IOException e) {
                try {out.close();} catch (IOException e1) {}
                try {origin.close();} catch (IOException e1) {}
                try {dest.close();} catch (IOException e1) {};
                Log.e(TAG, "Closing streams exception", e);
                return false;
            }
        }
        return true;
    }


    /**
     * Returns true if compressed successfully and false otherwise.
     */
    private boolean compressFile(){

        BufferedInputStream origin = null;
        ZipOutputStream out = null;

        String zipFileName = recordingName + Constants.ZIP_FILE_EXTENTION;
        String fileName = recordingName + Constants.TEXT_FILE_EXTENTION;
        String directoryAbsolutePath = Environment.getExternalStorageDirectory().toString()+ Constants.APP_DIRECTORY;
        File root = new File(directoryAbsolutePath);
        root.mkdirs();

        try {
            FileOutputStream dest = new FileOutputStream(root +"/"+ zipFileName);

            out = new ZipOutputStream(new BufferedOutputStream(dest));
            byte data[] = new byte[BUFFER];

            FileInputStream fi = new FileInputStream(context.getFilesDir() + "/" + fileName);
            origin = new BufferedInputStream(fi, BUFFER);

            ZipEntry entry = new ZipEntry(fileName.substring(fileName.lastIndexOf("/") + 1));
            out.putNextEntry(entry);
            int count;

            Long recordingSize = (new File(context.getFilesDir() + "/" + fileName)).length();
            long currentBitsCompressed = 0;
            while ((count = origin.read(data, 0, BUFFER)) != -1) {
                out.write(data, 0, count);
                currentBitsCompressed += BUFFER;
                sendPercentageToActivity((int)( currentBitsCompressed * 100 / recordingSize), STATE_COMPRESSING_FILE);
            }
            context.deleteFile(recordingName + Constants.TEXT_FILE_EXTENTION);

            // Tells the media scanner to scan the new compressed file, so that
            // it is visible for the user via USB without needing to reboot
            // device because of the MTP protocol
            Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            intent.setData(Uri.fromFile(new File(root + "/" + zipFileName)));
            context.sendBroadcast(intent);

        } catch (Exception e) {
            context.deleteFile(recordingName + Constants.TEXT_FILE_EXTENTION);
            Log.e(TAG, "Exception while zipping", e);
            return false;
        }
        finally{
            try {
                origin.close();
                out.close();
            } catch (IOException e) {
                try {out.close();} catch (IOException e1) {}
                Log.e(TAG, "Exception while closing streams", e);
                return false;
            }
        }
        return true;
    }

    /**
     * Used to send updates of the percentage of adding the header or compressing the file
     * to the client, to keep him informed while waiting
     */
    private void sendPercentageToActivity(int percentage, int state) {
        try {
            this.client.send(Message.obtain(null, MSG_PERCENTAGE, percentage, state));
        } catch (RemoteException e) {
            Log.e(TAG, "Exception sending percentage message to activity", e);
        }
    }

    /**
     * Returns true if writers were closed properly. False if an exception was
     * caught closing them
     */
    public boolean closeWriters(){
        try {
            bufferedWriter.flush();
            bufferedWriter.close();
            outStreamWriter.close();
        } catch (IOException e) {
            try {bufferedWriter.close();} catch (IOException e1) {}
            try {outStreamWriter.close();} catch (IOException e2) {}
            Log.e(TAG, "Exception while closing Writers", e);
            return false;
        }
        return true;
    }

    /**
     * Saves and compress a recording. Returns true if the writing and the
     * compression were successful or false if either one of them failed
     */
    public boolean saveAndCompressFile(Messenger client) {
        this.client = client;
        if(!enoughStorageAvailable())
            return false;
        if (!appendHeader())
            return false;
        if (!compressFile())
            return false;
        return true;
    }

    /**
     * Returns the internal storage available in bytes
     */
    @SuppressWarnings("deprecation")
    public long internalStorageAvailable() {
        StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long free = ((long)statFs.getAvailableBlocks() * (long)statFs.getBlockSize());// in Bytes [/1048576 -> in MB]
        return free;
    }

    /**
     * Returns the external storage available in bytes
     */
    @SuppressWarnings("deprecation")
    public long externalStorageAvailable() {
        StatFs statFs = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
        long free = ((long)statFs.getAvailableBlocks() * (long)statFs.getBlockSize());// in Bytes [/1048576 -> in MB]
        return free;
    }

    /**
     * Checks whether there is enough internal and external storage available to
     * save the recording.
     *
     * Returns true if there is enough storage or false otherwise
     */
    private boolean enoughStorageAvailable() {
        Long tmpFileSize = (new File(context.getFilesDir() + "/" + Constants.TEMP_FILE)).length();
        Log.i(TAG, "text file size: " + tmpFileSize);
        Log.i(TAG, "internal storage available: " + internalStorageAvailable());
        Log.i(TAG, "external storage available: " + externalStorageAvailable());
        boolean isEnough = false;

        if (internalStorageAvailable() > (tmpFileSize * 2 + 2 * 1048576)) // 2*tmpFile + 2MB
            isEnough = true;
        else {
            Log.e(TAG, "not enough internal storage to save raw recording");
            isEnough = false;
        }
        if(isEnough){
            if (externalStorageAvailable() > (tmpFileSize / 4))// compressed, weights 1/4 of the space
                isEnough = true;
            else {
                Log.e(TAG, "not enough external storage to save compressed recording");
                isEnough = false;
            }
        }

        return isEnough;
    }

    /**
     * Sets the duration of the recording
     */
    public void setDuration(String _duration) {
        this.duration = _duration;
    }

}