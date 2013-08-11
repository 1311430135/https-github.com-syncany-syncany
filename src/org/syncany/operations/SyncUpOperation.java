package org.syncany.operations;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.syncany.chunk.Deduper;
import org.syncany.config.Config;
import org.syncany.connection.plugins.RemoteFile;
import org.syncany.connection.plugins.StorageException;
import org.syncany.connection.plugins.TransferManager;
import org.syncany.database.Database;
import org.syncany.database.DatabaseVersion;
import org.syncany.database.MultiChunkEntry;
import org.syncany.database.VectorClock;
import org.syncany.util.FileUtil;
import org.syncany.util.StringUtil;

public class SyncUpOperation extends Operation {
	private static final Logger logger = Logger.getLogger(SyncUpOperation.class.getSimpleName());
	
	private TransferManager transferManager; 
	
	public SyncUpOperation(Config config) {
		super(config);
		transferManager = config.getConnection().createTransferManager();
	}	
	
	public void execute() throws Exception {
		logger.log(Level.INFO, "");
		logger.log(Level.INFO, "Running 'Sync up' at client "+profile.getMachineName()+" ...");
		logger.log(Level.INFO, "--------------------------------------------");
		
		logger.log(Level.INFO, "Loading local database ...");
		File localDatabaseFile = new File(profile.getAppDatabaseDir()+"/local.db");		
		Database db = loadLocalDatabase(localDatabaseFile);
		
		logger.log(Level.INFO, "Starting index process ...");
		List<File> localFiles = FileUtil.getRecursiveFileList(profile.getLocalDir());
		DatabaseVersion lastDirtyDatabaseVersion = index(localFiles, db);
		
		logger.log(Level.INFO, "Adding newest database version "+lastDirtyDatabaseVersion.getHeader()+" to local database ...");
		db.addDatabaseVersion(lastDirtyDatabaseVersion);

		logger.log(Level.INFO, "Saving local database to file "+localDatabaseFile+" ...");
		saveLocalDatabase(db, localDatabaseFile);
		
		logger.log(Level.INFO, "Uploading new multichunks ...");
		boolean uploadMultiChunksSuccess = uploadMultiChunks(db.getLastDatabaseVersion().getMultiChunks());
		
		if (uploadMultiChunksSuccess) {
			long newestLocalDatabaseVersion = lastDirtyDatabaseVersion.getVectorClock().get(profile.getMachineName());

			RemoteFile remoteDeltaDatabaseFile = new RemoteFile("db-"+profile.getMachineName()+"-"+newestLocalDatabaseVersion);
			File localDeltaDatabaseFile = profile.getCache().getDatabaseFile(remoteDeltaDatabaseFile.getName());	

			logger.log(Level.INFO, "Saving local delta database file ...");
			logger.log(Level.INFO, "- Saving versions from: "+lastDirtyDatabaseVersion.getHeader()+", to: "+lastDirtyDatabaseVersion.getHeader()+") to file "+localDeltaDatabaseFile+" ...");
			saveLocalDatabase(db, lastDirtyDatabaseVersion, lastDirtyDatabaseVersion, localDeltaDatabaseFile);
			
			logger.log(Level.INFO, "- Uploading local delta database file ...");
			uploadLocalDatabase(localDeltaDatabaseFile, remoteDeltaDatabaseFile);			
		}
		else {
			throw new Exception("aa");
		}		
	}	
	
	private boolean uploadMultiChunks(Collection<MultiChunkEntry> multiChunksEntries) throws InterruptedException, StorageException {

		for (MultiChunkEntry multiChunkEntry : multiChunksEntries) {
			File localMultiChunkFile = profile.getCache().getEncryptedMultiChunkFile(multiChunkEntry.getId());
			RemoteFile remoteMultiChunkFile = new RemoteFile(localMultiChunkFile.getName());
			
			logger.log(Level.INFO, "- Uploading multichunk "+StringUtil.toHex(multiChunkEntry.getId())+" from "+localMultiChunkFile+" to "+remoteMultiChunkFile+" ...");
			transferManager.upload(localMultiChunkFile, remoteMultiChunkFile);
			
			logger.log(Level.INFO, "  + Removing "+StringUtil.toHex(multiChunkEntry.getId())+" ...");
			localMultiChunkFile.delete();
		}
		
		return true; // FIXME
	}

	private boolean uploadLocalDatabase(File localDatabaseFile, RemoteFile remoteDatabaseFile) throws InterruptedException, StorageException {		
		logger.log(Level.INFO, "- Uploading "+localDatabaseFile+" to "+remoteDatabaseFile+" ..."); 		
		transferManager.upload(localDatabaseFile, remoteDatabaseFile);
		
		return true;
	}

	private DatabaseVersion index(List<File> localFiles, Database db) throws FileNotFoundException, IOException {			
		// Get last vector clock
		String previousClient = null;
		VectorClock lastVectorClock = null;
		
		if (db.getLastDatabaseVersion() != null) {
			previousClient = db.getLastDatabaseVersion().getClient();
			lastVectorClock = db.getLastDatabaseVersion().getVectorClock();
		}
		else {
			previousClient = null;
			lastVectorClock = new VectorClock();
		}
		
		// New vector clock
		VectorClock newVectorClock = lastVectorClock.clone();

		Long lastLocalValue = lastVectorClock.getClock(profile.getMachineName());
		Long newLocalValue = (lastLocalValue == null) ? 1 : lastLocalValue+1;
		
		newVectorClock.setClock(profile.getMachineName(), newLocalValue);		

		// Index
		Deduper deduper = new Deduper(profile.getChunker(), profile.getMultiChunker(), profile.getTransformer());
		Indexer indexer = new Indexer(profile, deduper, db);
		
		DatabaseVersion newDatabaseVersion = indexer.index(localFiles);	
	
		newDatabaseVersion.setVectorClock(newVectorClock);
		newDatabaseVersion.setTimestamp(new Date());	
		newDatabaseVersion.setClient(profile.getMachineName());
		newDatabaseVersion.setPreviousClient(previousClient);
						
		return newDatabaseVersion;
	}
}
