package us.dot.its.jpo.ode.importer;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import us.dot.its.jpo.ode.OdeProperties;
import us.dot.its.jpo.ode.coder.FileAsn1CodecPublisher;
import us.dot.its.jpo.ode.eventlog.EventLogger;
// Removed for ODE-559
//import us.dot.its.jpo.ode.coder.FileDecoderPublisher;
import us.dot.its.jpo.ode.importer.ImporterDirectoryWatcher.ImporterFileType;

public class ImporterProcessor {

   private static final Logger logger = LoggerFactory.getLogger(ImporterProcessor.class);
// Removed for ODE-559
//   private FileDecoderPublisher decoderPublisherManager;
   private FileAsn1CodecPublisher codecPublisher;
   private OdeProperties odeProperties;
   private ImporterFileType fileType;

   public ImporterProcessor(OdeProperties odeProperties, ImporterFileType fileType) {
   // Removed for ODE-559
//      this.decoderPublisherManager = new FileDecoderPublisher(odeProperties);
      this.codecPublisher = new FileAsn1CodecPublisher(odeProperties);
      this.odeProperties = odeProperties;
      this.fileType = fileType;
   }

   public void processDirectory(Path dir, Path backupDir, Path failureDir) {
      int count = 0;
      // Process files already in the directory
      //logger.debug("Started processing files at location: {}", dir);
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {

         for (Path entry : stream) {
            if (entry.toFile().isDirectory()) {
               processDirectory(entry, backupDir, failureDir);
            } else {
               logger.debug("Found a file to process: {}", entry.getFileName());
               processAndBackupFile(entry, backupDir, failureDir);
               count++;
            }
         }

         //logger.debug("Finished processing {} files at location: {}", count, dir);
      } catch (Exception e) {
         logger.error("Error processing files.", e);
      }
   }

   public void processAndBackupFile(Path filePath, Path backupDir, Path failureDir) {

      /*
       * ODE-559 vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv
       * removed lines below when asn1_codec was integrated
       */
//      try (InputStream inputStream = new FileInputStream(filePath.toFile())) {
//         BufferedInputStream bis = new BufferedInputStream(inputStream, odeProperties.getImportProcessorBufferSize());
//         decoderPublisherManager.decodeAndPublishFile(filePath, bis, fileType);
//         bis = new BufferedInputStream(inputStream, odeProperties.getImportProcessorBufferSize());
//      } catch (Exception e) {
//         logger.error("Unable to open or process file: " + filePath, e);
//      }
      // ODE-559 ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

      // ODE-559
      boolean success = true;
      try (InputStream inputStream = new FileInputStream(filePath.toFile())) {
         BufferedInputStream bis = new BufferedInputStream(inputStream, odeProperties.getImportProcessorBufferSize());
         codecPublisher.publishFile(filePath, bis, fileType);
      } catch (Exception e) {
         success = false;
         logger.error("Failed to open or process file: " + filePath, e);
         EventLogger.logger.error("Failed to open or process file: " + filePath, e);  
      }
      
      try {
         if (success) {
            OdeFileUtils.backupFile(filePath, backupDir);
            logger.info("File moved to backup: {}", backupDir);
            EventLogger.logger.info("File moved to backup: {}", backupDir);  
         } else {
            OdeFileUtils.moveFile(filePath, failureDir);
            logger.info("File moved to failure directory: {}", failureDir);  
            EventLogger.logger.info("File moved to failure directory: {}", failureDir);
         }
      } catch (IOException e) {
         logger.error("Unable to backup file: " + filePath, e);
      }
   }
}
