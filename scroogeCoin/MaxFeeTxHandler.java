import java.util.*;

public class MaxFeeTxHandler {

    protected UTXOPool utxoPool;
    protected UTXOPool originalUtxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public MaxFeeTxHandler(UTXOPool utxoPool) {

        this.utxoPool = utxoPool;
        this.originalUtxoPool = new UTXOPool(utxoPool);
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

    private Set<UTXO> getOutputUTXOs(Transaction tx) {

        Set<UTXO> outputUTXOs = new HashSet<>();

        for(int i=0; i<tx.getOutputs().size(); i++) {
            UTXO utxo = new UTXO(tx.getHash(), i);
            outputUTXOs.add(utxo);
        }

        return outputUTXOs;

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

    private Transaction getMaxFee(List<Transaction> transactions, UTXOPool pool) {

        double maxFee = 0;
        Transaction bestTransaction = null;
        for(Transaction tx: transactions) {
            double fee = getFee(tx, pool);
            if(fee > maxFee) {
                bestTransaction = tx;
            }
        }

        return bestTransaction;
    }

    private double getTransactionsFee(List<Transaction> transactions, HashMap<Transaction, Double> feeTable) {

        double fee = 0;
        for(Transaction tx : transactions) {
            fee += feeTable.get(tx);
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

    public void sortTransactions(List<Transaction> transactions, HashMap<Transaction, Double> feeTable) {

        Collections.sort(transactions, new Comparator<Transaction>() {
            public int compare(Transaction t1, Transaction t2) {
                Double t1Fee = feeTable.get(t1);
                Double t2Fee = feeTable.get(t2);
                return t2Fee.compareTo(t1Fee);
            }
        });
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

    public List<Transaction> removeMultipleClaimToSameOutputTransactions(List<Transaction> transactions) {

        List<Transaction> valid = new LinkedList<>();
        for(Transaction tx: transactions) {
            if(!verifyNoUTXOClaimedMultipleTimes(tx)){
                valid.add(tx);
            }
        }
        return valid;
    }

    private <T> boolean hasIntersection(Set<T> s1, Set<T> s2) {

        for(T t : s1) {
            if(s2.contains(t)) {
                return true;
            }
        }

        return false;
    }

    private boolean isClaimedConsumed(Transaction tx, Set<UTXO> consumedCoins) {

        for(UTXO claimed : getClaimedUTXOs(tx)) {
            if(consumedCoins.contains(claimed)){
                return true;
            }
        }

        return false;
    }

    private void removeTransactionWithConsumedCoins(List<Transaction> avaiable, Set<UTXO> consumedCoins) {

        List<Transaction> toRemove;
        do {

            toRemove = new LinkedList<>();

            for (Transaction tx : avaiable) {

                if (isClaimedConsumed(tx, consumedCoins)) {
                    consumedCoins.addAll(getOutputUTXOs(tx));
                    toRemove.add(tx);
                }
            }

            avaiable.removeAll(toRemove);
        }while(!toRemove.isEmpty());
    }

    private HashMap<Transaction, Double> generateFeeTable(List<Transaction> transactions) {

        UTXOPool pool = new UTXOPool(originalUtxoPool);
        for(Transaction tx : transactions) {
            for(int i=0; i<tx.getOutputs().size(); i++) {
                Transaction.Output out = tx.getOutput(i);
                UTXO utxo = new UTXO(tx.getHash(), i);

                pool.addUTXO(utxo, out);
            }
        }

        HashMap<Transaction, Double> feeTable = new HashMap<>();
        for(Transaction tx : transactions) {
            feeTable.put(tx, getFee(tx, pool));
        }
        return feeTable;
    }

    public Transaction[] handleTxs(Transaction[] possibleTxs) {

        List<Transaction> avaiable = new LinkedList<>(Arrays.asList(possibleTxs));
        List<Transaction> maxFee = combinatorialIndependentGroupsHandleTxs(avaiable);
        return maxFee.toArray(new Transaction[maxFee.size()]);
    }

    public List<Transaction> greedyHandleTxs(List<Transaction> avaiable) {
        List<Transaction> best = new LinkedList<>();

        List<Transaction> valid = getValidTransactions(avaiable, utxoPool);
        while(!valid.isEmpty()) {

            Transaction bestFeeTx = getMaxFee(valid, utxoPool);
            best.add(bestFeeTx);
            avaiable.remove(bestFeeTx);

            apply(bestFeeTx, utxoPool);

            valid = getValidTransactions(avaiable, utxoPool);
        }

        return best;
    }

    public List<Transaction> combinatorialIndependentGroupsHandleTxs(List<Transaction> avaiable) {

//        List<Transaction> validTransactions = removeNegativeOutputTransactions(avaiable);
//        validTransactions = removeMultipleClaimToSameOutputTransactions(validTransactions);

        List<List<Transaction>> independentGroups = findIndependentTransactions(avaiable);
        List<Transaction> allProcessedTransanctions = new LinkedList<>();

        for(List<Transaction> subGroup : independentGroups) {
            List<Transaction> processedTransactions = combinatorialHandleTxs(subGroup);
            allProcessedTransanctions.addAll(processedTransactions);
        }

        List<Transaction> validatedProcessedTransactions = new LinkedList<>();
        for(Transaction tx : allProcessedTransanctions) {
            if(isValidTx(tx, utxoPool)){
                apply(tx, utxoPool);
                validatedProcessedTransactions.add(tx);
            }
        }

        for(Transaction tx : avaiable) {
            if(isValidTx(tx, utxoPool)){
                apply(tx, utxoPool);
                validatedProcessedTransactions.add(tx);
            }
        }

        return validatedProcessedTransactions;
    }

    private double bestFee;
    private List<Transaction> maxFeeTransactions;
    private HashMap<Transaction, Double> feeTable;

    private int maxIterations = 10000;
    private int numIterations = 0;

    public List<Transaction> combinatorialHandleTxs(List<Transaction> possibleTxs) {

        numIterations = 0;

        //calc hash for all transactions
        for(Transaction tx : possibleTxs) {
            tx.finalize();
        }

        feeTable = generateFeeTable(possibleTxs);
        bestFee = 0;
        maxFeeTransactions = new LinkedList<>();

        sortTransactions(possibleTxs, feeTable);
        for(Transaction tx: possibleTxs) {
            invokeCombinatorial(possibleTxs, new LinkedList<>(), new UTXOPool(utxoPool), new HashSet<UTXO>(), tx);
        }

        return maxFeeTransactions;
    }

    public void combinatorialHandleTxs(List<Transaction> avaiableTransactions, List<Transaction>  currentTransactions, UTXOPool pool, Set<UTXO> consumedCoins) {

//        numIterations++;
//        if(numIterations > maxIterations) {
//            return;
//        }

        List<Transaction> validTransactions = getValidTransactions(avaiableTransactions, pool);

        double currentFee = getTransactionsFee(currentTransactions, feeTable);
        double avaiableFee = getTransactionsFee(avaiableTransactions, feeTable);

        if(validTransactions.isEmpty()) {
            if(currentFee > bestFee) {
                bestFee = currentFee;
                maxFeeTransactions = currentTransactions;
            }
        }

        if(currentFee + avaiableFee <= bestFee) {
            return;
        }

        for(Transaction tx : validTransactions) {
            invokeCombinatorial(avaiableTransactions, currentTransactions, pool, consumedCoins, tx);
        }
    }

    private void invokeCombinatorial(List<Transaction> avaiableTransactions, List<Transaction> currentTransactions, UTXOPool pool, Set<UTXO> consumedCoins, Transaction tx) {

        UTXOPool poolClone = new UTXOPool(pool);

        List<Transaction> newAvaiable = new LinkedList<>(avaiableTransactions);
        newAvaiable.remove(tx);

        List<Transaction> newCurrent = new LinkedList<>(currentTransactions);
        newCurrent.add(tx);

        apply(tx, poolClone);

        Set<UTXO> newConsumedCoins = new HashSet<>(consumedCoins);
        newConsumedCoins.addAll(getClaimedUTXOs(tx));

        removeTransactionWithConsumedCoins(newAvaiable, newConsumedCoins);

        combinatorialHandleTxs(newAvaiable, newCurrent, poolClone, newConsumedCoins);
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

    private List<List<Transaction>> findIndependentTransactions(List<Transaction> avaiable) {

        QuickFindUF uf = new QuickFindUF(avaiable.size());

        for(int i=0; i<avaiable.size(); i++) {
            for(int j=0; j<avaiable.size(); j++) {

                Transaction t1 = avaiable.get(i);
                Transaction t2 = avaiable.get(j);

                if(isConnected(t1, t2)){
                    uf.union(i, j);
                }
            }
        }

        List<List<Integer>> allIndexes = uf.getComponents();
        List<List<Transaction>> allGroups = new LinkedList<>();

        for(List<Integer> indexes : allIndexes) {

            List<Transaction> group = new LinkedList<>();
            for(Integer index : indexes) {
                group.add(avaiable.get(index));
            }
            allGroups.add(group);
        }

        return allGroups;
    }

    private boolean isConnected(Transaction t1, Transaction t2) {

        Set<UTXO> outputT1 = getOutputUTXOs(t1);
        Set<UTXO> inputT1 = getClaimedUTXOs(t1);
        Set<UTXO> inputT2 = getClaimedUTXOs(t2);

        return hasIntersection(outputT1, inputT2) || hasIntersection(inputT1, inputT2);
    }

    /**
     * QuickFindUF from https://algs4.cs.princeton.edu/15uf/QuickFindUF.java.html
     */
    private class QuickFindUF {
        private int[] id;    // id[i] = component identifier of i
        private int count;   // number of components

        public QuickFindUF(int n) {
            count = n;
            id = new int[n];
            for (int i = 0; i < n; i++)
                id[i] = i;
        }

        public List<List<Integer>> getComponents() {

            List<List<Integer>> allComponentes = new LinkedList<>();

            for(int groupId=0; groupId<id.length; groupId++) {

                List<Integer> components = new LinkedList<>();
                for(int index=0; index<id.length; index++) {
                    if(id[index] == groupId) {
                        components.add(index);
                    }
                }

                if(!components.isEmpty()){
                    allComponentes.add(components);
                }
            }

            return allComponentes;
        }

        public int count() {
            return count;
        }

        public int find(int p) {
            validate(p);
            return id[p];
        }

        private void validate(int p) {
            int n = id.length;
            if (p < 0 || p >= n) {
                throw new IllegalArgumentException("index " + p + " is not between 0 and " + (n-1));
            }
        }

        public boolean connected(int p, int q) {
            validate(p);
            validate(q);
            return id[p] == id[q];
        }

        public void union(int p, int q) {
            validate(p);
            validate(q);
            int pID = id[p];   // needed for correctness
            int qID = id[q];   // to reduce the number of array accesses

            // p and q are already in the same component
            if (pID == qID) return;

            for (int i = 0; i < id.length; i++)
                if (id[i] == pID) id[i] = qID;
            count--;
        }
    }



}
