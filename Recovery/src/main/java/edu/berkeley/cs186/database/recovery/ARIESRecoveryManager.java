package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.*;
//import jdk.internal.access.JavaNetUriAccess;
//import jdk.jpackage.internal.Log;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement
        LogRecord commitRecord = new CommitTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN);
        long returnLSN = logManager.appendToLog(commitRecord);
        Transaction updatedTx= transactionTable.get(transNum).transaction;
        updatedTx.setStatus(Transaction.Status.COMMITTING);
        transactionTable.get(transNum).lastLSN = returnLSN;
        logManager.flushToLSN(returnLSN);
        return returnLSN;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement
        //append the log
        LogRecord abortRecord = new AbortTransactionLogRecord(transNum,transactionTable.get(transNum).lastLSN);
        long returnLSN = logManager.appendToLog(abortRecord);
        //update transaction table and status
        Transaction updatedTx= transactionTable.get(transNum).transaction;
        updatedTx.setStatus(Transaction.Status.ABORTING);
        transactionTable.get(transNum).lastLSN = returnLSN;
        return returnLSN;

    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        Transaction endTx= transactionTable.get(transNum).transaction;
        if(endTx.getStatus().equals(Transaction.Status.ABORTING)){
            rollbackToLSN(transNum,0);
        }
        // add log
        LogRecord endRecord = new EndTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN);
        // update Tx
        endTx.setStatus(Transaction.Status.COMPLETE);
        long returnLSN = logManager.appendToLog(endRecord);
        transactionTable.get(transNum).lastLSN = returnLSN;
        transactionTable.remove(transNum);
        return returnLSN;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     *    - if the record at the current LSN is undoable:
     *       - Get a compensation log record (CLR) by calling undo on the record
     *       - Append the CLR
     *       - Call redo on the CLR to perform the undo
     *    - update the current LSN to that of the next record to undo
     *
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN LSN to which we should rollback
     */
    private void rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        LogRecord lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        long lastRecordLSN = lastRecord.getLSN();
        // Small optimization: if the last record is a CLR we can start rolling
        // back from the next record that hasn't yet been undone.
        long currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);
        // TODO(proj5) implement the rollback logic described above
        while(currentLSN>LSN){
            LogRecord currentRecord = logManager.fetchLogRecord(currentLSN);
            if(currentRecord.isUndoable()){
                LogRecord clrRecord = currentRecord.undo(transactionTable.get(transNum).lastLSN);
                transactionEntry.lastLSN =logManager.appendToLog(clrRecord);
                clrRecord.redo(this,diskSpaceManager,bufferManager);
            }
            currentLSN = currentRecord.getPrevLSN().get();

        }
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // TODO(proj5): implement
        //create+append
        long lastLSN = transactionTable.get(transNum).lastLSN;
        LogRecord flushRecord = new UpdatePageLogRecord(transNum,pageNum,lastLSN,pageOffset,before,after);
        long returnLSN = logManager.appendToLog(flushRecord);
        //update transaction table and status
        transactionTable.get(transNum).lastLSN = returnLSN;
        //dirty table(page number -> recLSN)
        if(dirtyPageTable.containsKey(pageNum)){
            if(dirtyPageTable.get(pageNum)>returnLSN)
                dirtyPageTable.put(pageNum,returnLSN);
        }
        else{
            dirtyPageTable.put(pageNum,returnLSN);
        }
        return returnLSN;

    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        rollbackToLSN(transNum,savepointLSN);
        return;
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> chkptDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = new HashMap<>();

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table
        Iterator<Map.Entry<Long,Long>> dirtyitr = dirtyPageTable.entrySet().iterator();
        Iterator<Map.Entry<Long,TransactionTableEntry>> Txitr = transactionTable.entrySet().iterator();
        int numDPTRecords= 0;
        int numTxnTableRecords= 0;
        LogRecord endRecord;
        while(dirtyitr.hasNext()||Txitr.hasNext()){
            if(dirtyitr.hasNext()){
                if(EndCheckpointLogRecord.fitsInOneRecord(numDPTRecords+1,numTxnTableRecords)){
                    Map.Entry<Long,Long> entry1 = dirtyitr.next();
                    chkptDPT.put(entry1.getKey(),entry1.getValue());
                    numDPTRecords++;
                }
                else{
                    endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                    logManager.appendToLog(endRecord);
                    flushToLSN(endRecord.getLSN());
                    chkptDPT.clear();
                    numDPTRecords = 0;
                }
            }
            else{
                if(EndCheckpointLogRecord.fitsInOneRecord(numDPTRecords,numTxnTableRecords+1)){
                    Map.Entry<Long,TransactionTableEntry> entry2 = Txitr.next();
                    Pair<Transaction.Status,Long> entry2val = new Pair<>(entry2.getValue().transaction.getStatus(),entry2.getValue().lastLSN);
                    chkptTxnTable.put(entry2.getKey(),entry2val);
                    numTxnTableRecords++;
                }
                else{
                    endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                    logManager.appendToLog(endRecord);
                    flushToLSN(endRecord.getLSN());
                    chkptDPT.clear();
                    chkptTxnTable.clear();
                    numDPTRecords = 0;
                    numTxnTableRecords =0;
                }
            }
        }
        // Last end checkpoint record
        endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
        logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN,v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     *
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     *
     * If the log record is page-related (getPageNum is present), update the dpt
     *   - update/undoupdate page will dirty pages
     *   - free/undoalloc page always flush changes to disk
     *   - no action needed for alloc/undofree page
     *
     * If the log record is for a change in transaction status:
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     *   from txn table, and add to endedTransactions
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     *   the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     *   to transition from the status in the table to the status in the
     *   checkpoint. For example, running -> aborting is a possible transition,
     *   but aborting -> running is not.
     *
     * After all records in the log are processed, for each table entry:
     *  - if COMMITTING: clean up the transaction, change status to COMPLETE,
     *    remove from the table, and append an end record
     *  - if RUNNING: change status to RECOVERY_ABORTING, and append an abort
     *    record
     *  - if RECOVERY_ABORTING: no action needed
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        // TODO(proj5): implement
        Iterator<LogRecord> logitr = logManager.scanFrom(LSN);
        LogRecord nextRecord=null;
        if(logitr.hasNext())
            nextRecord = logitr.next();
        while(nextRecord!=null){
                if(nextRecord.getTransNum().isPresent()){
                    if(!transactionTable.containsKey(nextRecord.getTransNum().get())){
                        Transaction addTx = this.newTransaction.apply(nextRecord.getTransNum().get());
                        TransactionTableEntry newEntry = new TransactionTableEntry(addTx);
                        newEntry.lastLSN = nextRecord.getLSN();
                        transactionTable.put(nextRecord.getTransNum().get(), newEntry);
                    }
                    else {
                        if(nextRecord.getLSN()>=transactionTable.get(nextRecord.getTransNum().get()).lastLSN){
                            transactionTable.get(nextRecord.getTransNum().get()).lastLSN=nextRecord.getLSN();
                        }
                    }
                }
                if(nextRecord.getPageNum().isPresent()){
                    if(nextRecord.getType().equals(LogType.UPDATE_PAGE)||nextRecord.getType().equals(LogType.UNDO_UPDATE_PAGE)){
                        Long pageNum = nextRecord.getPageNum().get();
                        Long currentLSN = dirtyPageTable.getOrDefault(pageNum, nextRecord.getLSN());
                        if (currentLSN >= nextRecord.getLSN()) {
                            dirtyPageTable.put(pageNum, nextRecord.getLSN());
                        }
                    }
                    if(nextRecord.getType().equals(LogType.FREE_PAGE)||nextRecord.getType().equals(LogType.UNDO_ALLOC_PAGE)){
                        flushToLSN(nextRecord.getLSN());
                        dirtyPageTable.remove(nextRecord.getPageNum().get());
                    }
                }
                if(nextRecord.getType().equals(LogType.COMMIT_TRANSACTION)){
                    transactionTable.get(nextRecord.getTransNum().get()).transaction.setStatus(Transaction.Status.COMMITTING);
                }
                if(nextRecord.getType().equals(LogType.ABORT_TRANSACTION)){
                    transactionTable.get(nextRecord.getTransNum().get()).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                }
                if(nextRecord.getType().equals(LogType.END_TRANSACTION)){
                    transactionTable.get(nextRecord.getTransNum().get()).transaction.cleanup();
                    transactionTable.get(nextRecord.getTransNum().get()).transaction.setStatus(Transaction.Status.COMPLETE);
                    this.transactionTable.remove(nextRecord.getTransNum().get());
                    endedTransactions.add(nextRecord.getTransNum().get());
                }
                if(nextRecord.getType().equals(LogType.END_CHECKPOINT)){
                    if(!nextRecord.getDirtyPageTable().isEmpty()){
                        Iterator<Map.Entry<Long,Long>> dirtyitr = nextRecord.getDirtyPageTable().entrySet().iterator();
                        while(dirtyitr.hasNext()){
                            Map.Entry<Long,Long> dirtyRecord = dirtyitr.next();
                            dirtyPageTable.put(dirtyRecord.getKey(),dirtyRecord.getValue());
                        }
                    }
                    if(!nextRecord.getTransactionTable().isEmpty()){
                        Iterator<Map.Entry<Long,Pair<Transaction.Status,Long>>> Txitr = nextRecord.getTransactionTable().entrySet().iterator();
                        while(Txitr.hasNext()){
                            Map.Entry<Long,Pair<Transaction.Status,Long>> nextTx = Txitr.next();
                            if(endedTransactions.contains(nextTx.getKey())) continue;
                            if(!transactionTable.containsKey(nextTx.getKey())){
                                Transaction addTx = this.newTransaction.apply(nextTx.getKey());
                                Transaction.Status setStatus = nextTx.getValue().getFirst().equals(Transaction.Status.ABORTING) ? Transaction.Status.RECOVERY_ABORTING : nextTx.getValue().getFirst();
                                addTx.setStatus(setStatus);
                                TransactionTableEntry newEntry = new TransactionTableEntry(addTx);
                                newEntry.lastLSN = nextTx.getValue().getSecond();
                                transactionTable.put(nextTx.getKey(), newEntry);
                            }
                            else{
                                if(nextTx.getValue().getSecond()>=transactionTable.get(nextTx.getKey()).lastLSN)
                                    transactionTable.get(nextTx.getKey()).lastLSN=nextTx.getValue().getSecond();
                                Transaction.Status current = transactionTable.get(nextTx.getKey()).transaction.getStatus();
                                Transaction.Status update = nextTx.getValue().getFirst();
                                if((current.equals(Transaction.Status.RUNNING) && (update.equals(Transaction.Status.COMMITTING)||update.equals(Transaction.Status.COMPLETE)))
                                        ||(current.equals(Transaction.Status.COMMITTING) && (update.equals(Transaction.Status.COMPLETE)))
                                        ||(current.equals(Transaction.Status.ABORTING) && (update.equals(Transaction.Status.COMPLETE)))){
                                    transactionTable.get(nextTx.getKey()).transaction.setStatus(update);
                                }
                                if(current.equals(Transaction.Status.RUNNING)&&update.equals(Transaction.Status.ABORTING))
                                    transactionTable.get(nextTx.getKey()).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                            }
                        }
                    }
                }
                if(logitr.hasNext()) nextRecord= logitr.next();
                else break;
        }
        for(Long Txnum : transactionTable.keySet()){
            Transaction.Status curr = transactionTable.get(Txnum).transaction.getStatus();
            if(curr.equals(Transaction.Status.COMMITTING)){
                transactionTable.get(Txnum).transaction.cleanup();
                transactionTable.get(Txnum).transaction.setStatus(Transaction.Status.COMPLETE);
                LogRecord newRecord = new EndTransactionLogRecord(Txnum,transactionTable.get(Txnum).lastLSN);
                transactionTable.remove(Txnum);
                logManager.appendToLog(newRecord);
            }
            else if(curr.equals(Transaction.Status.RUNNING)){
                transactionTable.get(Txnum).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                LogRecord newRecord = new AbortTransactionLogRecord(Txnum,transactionTable.get(Txnum).lastLSN);
                long returnLSN = logManager.appendToLog(newRecord);
                transactionTable.get(Txnum).lastLSN = returnLSN;
            }
        }
    }

    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the dirty page table.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - partition-related (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     *   the dirty page table with LSN >= recLSN, the page is fetched from disk,
     *   the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // TODO(proj5): implement
        if(!dirtyPageTable.isEmpty()){
            Long startPoint = null;
            Iterator<Map.Entry<Long,Long>> dirtyitr = dirtyPageTable.entrySet().iterator();
            Map.Entry<Long,Long> current = dirtyitr.next();
            while(current!=null){
                Long cur = current.getValue();
                startPoint = (startPoint==null) ? cur : Math.min(startPoint,cur);
                if(dirtyitr.hasNext()) current = dirtyitr.next();
                else break;
            }
            Iterator<LogRecord> logitr = logManager.scanFrom(startPoint);
            LogRecord nextRecord=null;
            if(logitr.hasNext())
                nextRecord = logitr.next();
            while(nextRecord!=null){
                if(nextRecord.isRedoable()){
                    LogType logType = nextRecord.getType();
                    if(logType == LogType.ALLOC_PART
                    ||logType == LogType.UNDO_ALLOC_PART
                    ||logType == LogType.FREE_PART
                    ||logType == LogType.UNDO_FREE_PART){
                        nextRecord.redo(this,diskSpaceManager,bufferManager);
                    }
                    if(logType == LogType.UPDATE_PAGE
                    ||logType == LogType.UNDO_UPDATE_PAGE
                    ||logType == LogType.UNDO_ALLOC_PAGE
                    ||logType == LogType.FREE_PAGE){
                        if(dirtyPageTable.containsKey(nextRecord.getPageNum().get())){
                            if(dirtyPageTable.get(nextRecord.getPageNum().get())<=nextRecord.getLSN()){
                                Page page = bufferManager.fetchPage(new DummyLockContext(), nextRecord.getPageNum().get());
                                try {
                                    long pageLSN = page.getPageLSN();
                                    if(pageLSN < nextRecord.getLSN()){
                                        nextRecord.redo(this,diskSpaceManager,bufferManager);
                                    }
                                } finally {
                                    page.unpin();
                                }
                            }
                        }
                    }
                }
                if(logitr.hasNext()) nextRecord = logitr.next();
                else break;
            }
        return;
    }
    }

    /**
     * This method performs the undo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting
     * transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and append the appropriate CLR
     * - replace the entry with a new one, using the undoNextLSN if available,
     *   if the prevLSN otherwise.
     * - if the new LSN is 0, clean up the transaction, set the status to complete,
     *   and remove from transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        PriorityQueue<Pair<Long, Long>> pq = new PriorityQueue<>(new PairFirstReverseComparator());
        if(!transactionTable.isEmpty()){
            Iterator<Map.Entry<Long, TransactionTableEntry>> Txitr = this.transactionTable.entrySet().iterator();
            Map.Entry<Long, TransactionTableEntry> Txentry = Txitr.next();
            while(Txentry!=null){
                if(Txentry.getValue().transaction.getStatus().equals(Transaction.Status.RECOVERY_ABORTING)){
                    Pair<Long,Long> undoTx = new Pair<>(Txentry.getValue().lastLSN,Txentry.getKey());
                    pq.add(undoTx);
                }
                if(Txitr.hasNext()) Txentry = Txitr.next();
                else break;
            }
            while(!pq.isEmpty()){
                Pair<Long,Long> fetchTx = pq.poll();
                LogRecord current = logManager.fetchLogRecord(fetchTx.getFirst());
               // if(current.isUndoable()&&(current.getType().equals(LogType.UPDATE_PAGE))){
                if(current.isUndoable()){
                    LogRecord clrRecord = current.undo(transactionTable.get(fetchTx.getSecond()).lastLSN);
                    TransactionTableEntry entry = transactionTable.get(current.getTransNum().get());
                    entry.lastLSN =logManager.appendToLog(clrRecord);
                    clrRecord.redo(this,diskSpaceManager,bufferManager);
                }
                long currentLSN = current.getUndoNextLSN().orElse(current.getPrevLSN().get());
                if(currentLSN!=0){
                    Pair<Long,Long> undoTx = new Pair<>(currentLSN,fetchTx.getSecond());
                    pq.add(undoTx);
                }
                else{
                    transactionTable.get(fetchTx.getSecond()).transaction.cleanup();
                    LogRecord endRecord = new EndTransactionLogRecord(fetchTx.getSecond(), transactionTable.get(fetchTx.getSecond()).lastLSN);
                    long returnLSN = logManager.appendToLog(endRecord);
                    transactionTable.get(fetchTx.getSecond()).transaction.setStatus(Transaction.Status.COMPLETE);
                    transactionTable.get(fetchTx.getSecond()).lastLSN = returnLSN;
                    this.transactionTable.remove(fetchTx.getSecond());
                }
            }

        }
        return;
    }

    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////
    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
}
