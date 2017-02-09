package us.dot.its.jpo.ode.importer;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import us.dot.its.jpo.ode.OdeProperties;
import us.dot.its.jpo.ode.bsm.BsmCoder;
import us.dot.its.jpo.ode.eventlog.EventLogger;
import us.dot.its.jpo.ode.messageframe.MessageFrameCoder;

public class Importer implements Runnable {

    public static final int HEALTHY = 0; // 0000000
    public static final int NO_INBOX_FOLDER = 1; // 0000001
    public static final int NO_BACKUP_FOLDER = 2; // 0000010
    public static final int WATCH_SVC_ERROR = 4; // 0000100
    public static final int FATAL_ERROR = 15; // 0001111
    public static final int MOVE2BACKUP_ERROR = 16; // 0010000
    public static final int ASN_DECODING_ERROR = 32; // 0100000
    public static final int PROCESSING_EXISTING_ERROR = 64; // 1000000

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    // Importer file directories:
    private Path inboxFolderRoot;
    private Path inboxFolderBsm;          // BSM file upload location
    private Path inboxFolderMessageFrame; // MessageFrame (BSM with header) file upload location
    private Path backupFolder;            // Location files are moved to after processing

    // Coders
    //private Asn1Plugin asn1Coder;   // ASN.1 decoder/encoder
    private BsmCoder bsmCoder;
    private MessageFrameCoder messageFrameCoder;

    // status of the importer
    private int importerStatus = HEALTHY;

    // Stats
    public int processedFileCount = 0;
    public long startProcessingTime = 0;
    public long lastFileProcessedTime = 0;


    public int getImporterStatus() {
        return importerStatus;
    }

    public String getImporterErrors() {
        String errorDescription = "";
        if (importerStatus == HEALTHY) {
            errorDescription = "No errors, importer is working like a champ";
        } else {
            if ((importerStatus & NO_INBOX_FOLDER) == NO_INBOX_FOLDER) {
                if (!errorDescription.isEmpty()) {
                    errorDescription += " | ";
                }
                errorDescription += ("FATAL ERROR: Inbox folder was not found and was not created at this location: "
                        + inboxFolderRoot.toString());
            }
            if ((importerStatus & NO_BACKUP_FOLDER) == NO_BACKUP_FOLDER) {
                if (!errorDescription.isEmpty()) {
                    errorDescription += " | ";
                }
                errorDescription += ("FATAL ERROR: Backup folder was not found and was not created at this location: "
                        + backupFolder.toString());
            }
            if ((importerStatus & WATCH_SVC_ERROR) == WATCH_SVC_ERROR) {
                if (!errorDescription.isEmpty()) {
                    errorDescription += " | ";
                }
                errorDescription += ("FATAL ERROR: Could not start up the service that monitors for new files in folder "
                        + inboxFolderRoot.toString());
            }
            if ((importerStatus & MOVE2BACKUP_ERROR) == MOVE2BACKUP_ERROR) {
                if (!errorDescription.isEmpty()) {
                    errorDescription += " | ";
                }
                errorDescription += ("ERROR: Could not rename and move file from the inbox folder ("
                        + inboxFolderRoot.toString() + ") to backup folder (" + backupFolder.toString() + ")");
            }
            if ((importerStatus & ASN_DECODING_ERROR) == ASN_DECODING_ERROR) {
                if (!errorDescription.isEmpty()) {
                    errorDescription += " | ";
                }
                errorDescription += ("ERROR: Encountered error in decoding ASN.1 file");
            }
            if ((importerStatus & PROCESSING_EXISTING_ERROR) == PROCESSING_EXISTING_ERROR) {
                if (!errorDescription.isEmpty()) {
                    errorDescription += " | ";
                }
                errorDescription += ("ERROR: Could not process existing files in the inbox folder: "
                        + inboxFolderRoot.toString());
            }
        }
        return errorDescription;

    }

    public void verifyInboxFolder() throws IOException {
        /*
         * Checks if the BSM, Message Frame, and backup folders exist. If they don't they get
         * created.
         */

        if (!inboxFolderRoot.toFile().exists()) {
            logger.warn("IMPORTER - Root inbox Folder does not exist. Expected at: {}", inboxFolderRoot);
            try {
                Files.createDirectory(inboxFolderRoot);
            } catch (IOException e) {
                logger.error("IMPORTER - Could not create root inbox Folder {}", e);
                importerStatus |= NO_INBOX_FOLDER | NO_BACKUP_FOLDER;
                logger.info("IMPORTER - status: {}", importerStatus);
                throw e;
            }
            logger.info("IMPORTER - Root inbox folder create at: {}", inboxFolderRoot);
        } else {
            logger.info("IMPORTER - BSM Inbox Folder exists: {}", inboxFolderBsm.toString());
        }
        if (!inboxFolderBsm.toFile().exists()) {
            logger.warn("IMPORTER - BSM Inbox Folder does not exist. Expected at: {}", inboxFolderBsm);
            try {
                Files.createDirectory(inboxFolderBsm);
            } catch (IOException e) {
                logger.error("IMPORTER - Could not create BSM Inbox Folder {}", e);
                importerStatus |= NO_INBOX_FOLDER | NO_BACKUP_FOLDER;
                logger.info("IMPORTER - status: {}", importerStatus);
                throw e;
            }
            logger.info("IMPORTER - BSM inbox folder create at: {}", inboxFolderBsm);
        } else {
            logger.info("IMPORTER - BSM Inbox Folder exists: {}", inboxFolderBsm.toString());
        }
        if (!inboxFolderMessageFrame.toFile().exists()) {
            logger.warn("IMPORTER - Message Frame Inbox Folder does not exist. Expected at: {}", inboxFolderMessageFrame);
            try {
                Files.createDirectory(inboxFolderMessageFrame);
            } catch (IOException e) {
                logger.error("IMPORTER - Could not create Message Frame Inbox Folder {}", e);
                importerStatus |= NO_INBOX_FOLDER | NO_BACKUP_FOLDER;
                logger.info("IMPORTER - status: {}", importerStatus);
                throw e;
            }
            logger.info("IMPORTER - Message frame inbox folder create at: {}", inboxFolderMessageFrame);
        } else {
            logger.info("IMPORTER - Message Frame Inbox Folder exists: {}", inboxFolderMessageFrame);
        }
        if (!backupFolder.toFile().exists()) {
            logger.warn("IMPORTER - Backup Folder does not exist");
            try {
                Files.createDirectory(backupFolder);
            } catch (IOException e) {
                logger.error("IMPORTER - Could not create Backup Folder");
                importerStatus |= NO_BACKUP_FOLDER;
                logger.info("IMPORTER - status: {}", importerStatus);
                throw e;
            }
            logger.info("IMPORTER - Backup folder create at {} ", backupFolder);
        } else {
            logger.info("IMPORTER - Backup Folder exists {}", backupFolder);
        }
    }

    public Importer(OdeProperties odeProps)
            throws ClassNotFoundException, InstantiationException, IllegalAccessException {

        String uploadLocationRoot = odeProps.getUploadLocationRoot();
        String uploadLocationBsm = odeProps.getUploadLocationBsm();
        String uploadLocationMessageFrame = odeProps.getUploadLocationMessageFrame();
        try {
            inboxFolderRoot = Paths.get(uploadLocationRoot);
            inboxFolderBsm = Paths.get(uploadLocationBsm);
            inboxFolderMessageFrame = Paths.get(uploadLocationMessageFrame);
            backupFolder = Paths.get(uploadLocationRoot, "backup");
            verifyInboxFolder();

        } catch (Exception ex) {
            logger.error("IMPORTER -  Error verifying inbox folder: {}", ex);
            return;
        }
        logger.info("IMPORTER - Watching BSM inbox folder: {}", inboxFolderBsm);
        logger.info("IMPORTER - Watching Message Frame inbox folder: {}", inboxFolderMessageFrame);

        logger.info("IMPORTER - Instantiating BSM Coder...");
        bsmCoder = new BsmCoder(odeProps);
        logger.info("IMPORTER - Instantiating Message Frame Coder...");
        messageFrameCoder = new MessageFrameCoder(odeProps);

        logger.info("Publishing to {}", OdeProperties.KAFKA_TOPIC_J2735_BSM);
        logger.info("Publishing to {}", OdeProperties.KAFKA_TOPIC_J2735_MESSAGE_FRAME);
    }

    public Path getInboxFolderBsm() {
        return inboxFolderBsm;
    }

    public void setInboxFolderBsm(Path inboxFolderBsm) {
        this.inboxFolderBsm = inboxFolderBsm;
    }

    public Path getInboxFolderMessageFrame() {
        return inboxFolderMessageFrame;
    }

    public void setInboxFolderMessageFrame(Path inboxFolderMessageFrame) {
        this.inboxFolderMessageFrame = inboxFolderMessageFrame;
    }

    public Path getBackupFolder() {
        return backupFolder;
    }

    public void setBackupFolder(Path backupFolder) {
        this.backupFolder = backupFolder;
    }

    public void disposeOfProcessedFile(Path filePath) {
        try {
            EventLogger.logger.info("Disposing file");
            logger.info("Disposing file: {}", filePath.toString());
            // TODO(Cris): handle other file types here...
            String processedFileName = Integer.toString((int) System.currentTimeMillis()) + "-"
                    + filePath.getFileName().toString().replaceFirst("uper", "pbo");
            // TODO (Cris): move magic subfolder name into config
            Path targetPath = backupFolder.resolveSibling(Paths.get("backup", processedFileName));
            Files.move(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            importerStatus |= MOVE2BACKUP_ERROR;
            logger.error("Error moving file to temporary directory: {}", filePath.toString(), e);
            EventLogger.logger.info("Error moving file to temporary directory: {}", filePath.toString());
        }
    }

    public void processFile(Path filePath) throws Exception, InterruptedException {

        // TODO let's try to get rid of the need for retry
        int tryCount = 3;
        boolean fileProcessed = false;
        while (tryCount-- > 0) {
            try (InputStream inputStream = new FileInputStream(filePath.toFile())) {
                EventLogger.logger.info("Processing file {}", filePath.toFile());
                if (filePath.toString().endsWith("uper")) {
                    if (filePath.startsWith(inboxFolderBsm)) {
                        this.bsmCoder.decodeFromStreamAndPublish(inputStream, OdeProperties.KAFKA_TOPIC_J2735_BSM);
                    } else if (filePath.startsWith(inboxFolderMessageFrame)) {
                        this.messageFrameCoder.decodeFromStreamAndPublish(inputStream, OdeProperties.KAFKA_TOPIC_J2735_BSM);
                    } else {
                        throw new Exception("Unable to determine coder type from filepath");
                    }
                } else {
                    if (filePath.startsWith(inboxFolderBsm)) {
                        this.bsmCoder.decodeFromHexAndPublish(inputStream, OdeProperties.KAFKA_TOPIC_J2735_BSM);
                    } else if (filePath.startsWith(inboxFolderMessageFrame)) {
                        this.messageFrameCoder.decodeFromHexAndPublish(inputStream, OdeProperties.KAFKA_TOPIC_J2735_BSM);
                    } else {
                        throw new Exception("Unable to determine coder type from filepath");
                    }
                }
                fileProcessed = true;
                break;
            } catch (Exception e) {
                logger.info("unable to open file: {}", filePath + " retrying {}", tryCount + " more times", e);
                Thread.sleep(1000);
            }
        }

        if (fileProcessed) {
            disposeOfProcessedFile(filePath);
        } else {
            EventLogger.logger.info("Failed to process file: {} ", filePath.toFile());
            throw new Exception("Failed to process file: " + filePath);
        }
    }

    public void processExistingFiles(Path pathToFileDirectory) {
        
        logger.debug("IMPORTER -  Processing existing files at location: {}", pathToFileDirectory);

        // TODO (Cris): test to see what happens when new files are arriving
        // during the iteration
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pathToFileDirectory, "*.{uper, bsm}")) {
            for (Path entry : stream) {
                try {
                    logger.debug("IMPORTER - Found a file to process: {}", entry.getFileName());
                    processFile(entry);
                } catch (Exception e) {
                    throw new IOException("Error processing file {}", e);
                }
            }
        } catch (IOException e) {
            // IOException thrown by newDirectoryStream.
            importerStatus |= PROCESSING_EXISTING_ERROR;
            logger.error("IMPORTER -  Error validating import location.", e);
        }

    }

    @Override
    public void run() {
        
        Map<WatchKey, Path> keyPathMap = new HashMap<>();

        // If we have any fatal errors at this point we abort
        if ((importerStatus & FATAL_ERROR) != 0) {
            logger.error("Importer cannot run, see error above. Execution terminated.");
            return;
        }
        logger.info("IMPORTER - Service run initiated.");

        // Reset the stats
        processedFileCount = 0;
        startProcessingTime = System.currentTimeMillis();
        lastFileProcessedTime = 0;

        // First process any files that may already be in the directory.
        processExistingFiles(inboxFolderBsm);
        processExistingFiles(inboxFolderMessageFrame);

        // Create a new WatchService and register directories
        WatchService service;
        try {
            // Try to create a new WatchService
            service = inboxFolderRoot.getFileSystem().newWatchService();
            
            // Try to create new WatchKeys for tracked directories
            WatchKey keyBsm = inboxFolderBsm.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            logger.info("IMPORTER - WatchService key registered for BSM inbox folder: {}", inboxFolderBsm);
            
            WatchKey keyMessageFrame = inboxFolderMessageFrame.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            logger.info("IMPORTER - WatchService key registered for Message Frame inbox folder: {}", inboxFolderMessageFrame);
            
            keyPathMap.put(keyBsm, inboxFolderBsm);
            keyPathMap.put(keyMessageFrame, inboxFolderMessageFrame);
            
        } catch (Exception e) {
            logger.error("Error creating and registering watch service {}", e);
            //throw new Exception("Error creating and registering watch service {}", e);
            return;
        }

        boolean isRunning = true;
        while (isRunning) {
            
                // Start the infinite polling loop
                WatchKey key;
                try {
                    key = service.take();
                } catch (InterruptedException e) {
                    logger.error("Error getting key from watch service{}", e);
                    break;
                }

                // Dequeuing events
                Kind<?> kind = null;
                for (WatchEvent<?> watchEvent : key.pollEvents()) {
                    // Get the type of the event
                    kind = watchEvent.kind();
                    if (OVERFLOW == kind) {
                        continue; // loop
                    } else if (ENTRY_MODIFY == kind) {
                        // A new Path was created
                        @SuppressWarnings("unchecked")
                        
                        WatchEvent<Path> watchEventCurrent = (WatchEvent<Path>) watchEvent;
                        Path newPath = (Path) watchEventCurrent.context();
                        Path parentPath = keyPathMap.get(key);
                        newPath = parentPath.resolve(newPath);

                        logger.info("New file detected: {}", newPath);
                        EventLogger.logger.info("New file detected: {}", newPath);
                        //Path fullPath = Paths.get(inboxFolderRoot.toString(), newPath.toString());
                        try {
                            processFile(newPath);
                        } catch (Exception e) {
                            logger.error("Error processing file: {}", e);
                            break;
                        }
                    }
                }

                if (!key.reset()) {
                    keyPathMap.remove(key);
                    
                    if (keyPathMap.isEmpty()) {
                        isRunning = false; // end the loop
                    }
                }

        }
    }

}
