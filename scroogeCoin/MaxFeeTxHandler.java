import com.sun.javafx.scene.control.skin.VirtualFlow;

import java.util.*;

public class MaxFeeTxHandler {

    protected UTXOPool utxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public MaxFeeTxHandler(UTXOPool utxoPool) {
        this.utxoPool = utxoPool;
    }

    private boolean isValidTx(Transaction tx, UTXOPool pool) {
        return verifyClaimedOutputsInPool(tx, pool) &&
                verifyInputSignatures(tx, pool) &&
                verifyNoUTXOClaimedMultipleTimes(tx) &&
                verifyAllOutputNonNegatives(tx) &&
                verifyInputsValuesGreaterThanOrEqualToOutputValues(tx, pool);
    }

    /**
     * @return true if:
     * (1) all outputs claimed by {@code tx} are in the current UTXO pool,
     * (2) the signatures on each input of {@code tx} are valid,
     * (3) no UTXO is claimed multiple times by {@code tx},
     * (4) all of {@code tx}s output values are non-negative, and
     * (5) the sum of {@code tx}s input values is greater than or equal to the sum of its output
     *     values; and false otherwise.
     */
    public boolean isValidTx(Transaction tx) {
       return isValidTx(tx, utxoPool);
    }

    private boolean verifyClaimedOutputsInPool(Transaction tx, UTXOPool pool) {

        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            if(!pool.contains(claimedUTXO)) {
                return false;
            }
        }

        return true;
    }

    private boolean verifyInputSignatures(Transaction tx, UTXOPool pool) {

        for(int i=0; i<tx.getInputs().size(); i++) {

            Transaction.Input in = tx.getInput(i);
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            byte[] message = tx.getRawDataToSign(i);
            Transaction.Output prevOut = pool.getTxOutput(claimedUTXO);

            if(!Crypto.verifySignature(prevOut.address, message, in.signature)) {
                return false;
            }
        }

        return true;
    }

    private boolean verifyNoUTXOClaimedMultipleTimes(Transaction tx) {

       Set<UTXO> claimedUTXOs = getClaimedUTXOs(tx);
       return claimedUTXOs.size() == tx.getInputs().size();
    }

    private Set<UTXO> getClaimedUTXOs(Transaction tx) {
        Set<UTXO> claimedUTXOs = new HashSet<>();

        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            claimedUTXOs.add(claimedUTXO);
        }

        return claimedUTXOs;
    }

    private boolean verifyAllOutputNonNegatives(Transaction tx) {
        for(Transaction.Output out : tx.getOutputs()) {
            if(out.value < 0){
                return false;
            }
        }
        return true;
    }

    protected boolean verifyInputsValuesGreaterThanOrEqualToOutputValues(Transaction tx, UTXOPool pool) {
        return getInputSum(tx, pool) >= getOutputSum(tx);
    }

    protected double getInputSum(Transaction tx, UTXOPool pool) {
        double inputSum = 0;
        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            Transaction.Output prevOut = pool.getTxOutput(claimedUTXO);
            inputSum += prevOut.value;
        }
        return inputSum;
    }

    protected double getOutputSum(Transaction tx) {
        double outputSum = 0;
        for(Transaction.Output out : tx.getOutputs()) {
            outputSum += out.value;
        }
        return outputSum;
    }

    private double getFee(Transaction tx, UTXOPool pool) {
        return getInputSum(tx, pool) - getOutputSum(tx);
    }

    private double getFeeTransactions(List<Transaction> transactions, UTXOPool pool) {

        UTXOPool clone = new UTXOPool(pool);
        double fee = 0;

        for(Transaction tx : transactions) {
            fee += getFee(tx, clone);
            apply(tx, clone);
        }
        return fee;
    }

    private List<Transaction> getValidTransactions(List<Transaction> transactions, UTXOPool pool) {

        List<Transaction> valid = new LinkedList<>();
        for(Transaction tx: transactions) {
            if(isValidTx(tx, pool)) {
                valid.add(tx);
            }
        }

        return valid;
    }

    public List<Transaction> removeNegativeOutputTransactions(List<Transaction> transactions) {

        List<Transaction> positiveOutputs = new LinkedList<>();
        for(Transaction tx: transactions) {
            if(verifyAllOutputNonNegatives(tx)){
                positiveOutputs.add(tx);
            }
        }
        return positiveOutputs;
    }

    private List<Transaction> removeConflitantInputs(Transaction transaction, List<Transaction> avaiableTransactions) {

        Set<UTXO> transactionsClains = getClaimedUTXOs(transaction);

        List<Transaction> noInputConflict = new LinkedList<>();

        for(Transaction tx : avaiableTransactions) {

            Set<UTXO> avaiableClains = getClaimedUTXOs(transaction);

            if(!hasIntersection(transactionsClains, avaiableClains)) {
                noInputConflict.add(tx);
            }
        }

        return noInputConflict;
    }

    private <T> boolean hasIntersection(Set<T> s1, Set<T> s2) {

        for(T t : s1) {
            if(s2.contains(t)) {
                return true;
            }
        }

        return false;

    }

    private double bestFee;
    private List<Transaction> maxFeeTransactions;

    public Transaction[] handleTxs(Transaction[] possibleTxs) {
        return combinatorialHandleTxs(possibleTxs);
    }

    public Transaction[] combinatorialHandleTxs(Transaction[] possibleTxs) {

        //not working for 30 transctions
        if(possibleTxs.length > 10) {
            return possibleTxs;
        }

        //calc hash for all transactions
        for(Transaction tx : possibleTxs) {
            tx.finalize();
        }

        List<Transaction> validTransactions = removeNegativeOutputTransactions(Arrays.asList(possibleTxs));

        bestFee = 0;
        maxFeeTransactions = new LinkedList<>();

        for(Transaction tx: possibleTxs) {

            UTXOPool poolClone = new UTXOPool(utxoPool);

            List<Transaction> avaiableTransactions = new LinkedList<>(validTransactions);
            avaiableTransactions.remove(tx);

            List<Transaction> currentTransactions = new LinkedList<>();
            currentTransactions.add(tx);

            apply(tx, poolClone);

            combinatorialHandleTxs(avaiableTransactions, currentTransactions, poolClone);
        }


        for(Transaction tx : maxFeeTransactions) {
            apply(tx, utxoPool);
        }

        return maxFeeTransactions.toArray(new Transaction[maxFeeTransactions.size()]);
    }

    public void combinatorialHandleTxs(List<Transaction> avaiableTransactions, List<Transaction>  currentTransactions, UTXOPool pool) {

        List<Transaction> validTransactions = getValidTransactions(avaiableTransactions, pool);

        if(validTransactions.isEmpty()) {

            double fee = getFeeTransactions(currentTransactions, utxoPool);
            if(fee > bestFee) {
                bestFee = fee;
                maxFeeTransactions = currentTransactions;
            }
        }

        for(Transaction tx : validTransactions) {

            UTXOPool poolClone = new UTXOPool(pool);

            List<Transaction> newAvaiable = new LinkedList<>(avaiableTransactions);
            newAvaiable.remove(tx);

            List<Transaction> newCurrent = new LinkedList<>(currentTransactions);
            newCurrent.add(tx);

            apply(tx, poolClone);

            combinatorialHandleTxs(newAvaiable, newCurrent, poolClone);
        }

    }

    private void apply(Transaction tx, UTXOPool pool) {

        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            pool.removeUTXO(claimedUTXO);
        }

        for(int i=0; i<tx.getOutputs().size(); i++) {

            Transaction.Output out = tx.getOutput(i);
            UTXO utxo = new UTXO(tx.getHash(), i);

            pool.addUTXO(utxo, out);
        }
    }


}
