import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TxHandler {

    protected UTXOPool utxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public TxHandler(UTXOPool utxoPool) {
        this.utxoPool = utxoPool;
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
        return verifyClaimedOutputsInPool(tx) &&
                verifyInputSignatures(tx) &&
                verifyNoUTXOClaimedMultipleTimes(tx) &&
                verifyAllOutputNonNegatives(tx) &&
                verifyInputsValuesGreaterThanOrEqualToOutputValues(tx);
    }

    private boolean verifyClaimedOutputsInPool(Transaction tx) {

        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            if(!utxoPool.contains(claimedUTXO)) {
                return false;
            }
        }

        return true;
    }

    private boolean verifyInputSignatures(Transaction tx) {

        for(int i=0; i<tx.getInputs().size(); i++) {

            Transaction.Input in = tx.getInput(i);
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            byte[] message = tx.getRawDataToSign(i);
            Transaction.Output prevOut = utxoPool.getTxOutput(claimedUTXO);

            if(!Crypto.verifySignature(prevOut.address, message, in.signature)) {
                return false;
            }
        }

        return true;
    }

    private boolean verifyNoUTXOClaimedMultipleTimes(Transaction tx) {

        Set<UTXO> claimedUTXOs = new HashSet<>();

        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            claimedUTXOs.add(claimedUTXO);
        }

        return claimedUTXOs.size() == tx.getInputs().size();
    }

    private boolean verifyAllOutputNonNegatives(Transaction tx) {
        for(Transaction.Output out : tx.getOutputs()) {
            if(out.value < 0){
                return false;
            }
        }
        return true;
    }

    private boolean verifyInputsValuesGreaterThanOrEqualToOutputValues(Transaction tx) {
        return getInputSum(tx) >= getOutputSum(tx);
    }

    protected double getInputSum(Transaction tx) {
        double inputSum = 0;
        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            Transaction.Output prevOut = utxoPool.getTxOutput(claimedUTXO);
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

    /**
     * Handles each epoch by receiving an unordered array of proposed transactions, checking each
     * transaction for correctness, returning a mutually valid array of accepted transactions, and
     * updating the current UTXO pool as appropriate.
     */
    public Transaction[] handleTxs(Transaction[] possibleTxs) {

        List<Transaction> acceptedTransactions = new ArrayList<>();
        for(Transaction tx : possibleTxs) {

            if(isValidTx(tx)) {
                apply(tx);
                acceptedTransactions.add(tx);
            }
        }

        return acceptedTransactions.toArray(new Transaction[acceptedTransactions.size()]);

    }

    private void apply(Transaction tx) {

        tx.finalize();

        for(Transaction.Input in : tx.getInputs()) {
            UTXO claimedUTXO = new UTXO(in.prevTxHash, in.outputIndex);
            utxoPool.removeUTXO(claimedUTXO);
        }

        for(int i=0; i<tx.getOutputs().size(); i++) {

            Transaction.Output out = tx.getOutput(i);
            UTXO utxo = new UTXO(tx.getHash(), i);

            utxoPool.addUTXO(utxo, out);
        }
    }
}
