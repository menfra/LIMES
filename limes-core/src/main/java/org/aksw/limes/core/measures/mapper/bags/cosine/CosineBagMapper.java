package org.aksw.limes.core.measures.mapper.bags.cosine;

import com.google.common.collect.Multiset;
import no.uib.cipr.matrix.*;
import no.uib.cipr.matrix.sparse.FlexCompRowMatrix;
import org.aksw.limes.core.exceptions.NotYetImplementedException;
import org.aksw.limes.core.io.cache.ACache;
import org.aksw.limes.core.io.mapping.AMapping;
import org.aksw.limes.core.io.mapping.MappingFactory;
import org.aksw.limes.core.measures.mapper.bags.IBagMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Cedric Richter
 */
public class CosineBagMapper implements IBagMapper {
    @Override
    public AMapping getMapping(ACache source, ACache target, String sourceVar, String targetVar, String expression, double threshold) {
        throw new NotYetImplementedException("How should a bag be parsed?");
    }

    @Override
    public double getRuntimeApproximation(int sourceSize, int targetSize, double theta, Language language) {
        return 0;
    }

    @Override
    public double getMappingSizeApproximation(int sourceSize, int targetSize, double theta, Language language) {
        return 0;
    }

    @Override
    public String getName() {
        return "bag_cosine";
    }

    @Override
    public <T> AMapping getMapping(Map<String, Multiset<T>> source, Map<String, Multiset<T>> target, double threshold) {

        Index<String> srcIndex = new Index<>();
        Index<String> targetIndex = new Index<>();

        Index<T> objectIndex = new Index<>();


        List<MatrixEntries> srcMat = new ArrayList<>();
        List<MatrixEntries> targetMat = new ArrayList<>();

        for(Map.Entry<String, Multiset<T>> e: source.entrySet()){

            Multiset<T> bag = e.getValue();

            for(T b: bag.elementSet()){
                srcMat.add(new MatrixEntries(srcIndex.index(e.getKey()), objectIndex.index(b),
                                             bag.count(b)));
            }

        }

        for(Map.Entry<String, Multiset<T>> e: target.entrySet()){

            Multiset<T> bag = e.getValue();

            for(T b: bag.elementSet()){
                targetMat.add(new MatrixEntries(targetIndex.index(e.getKey()), objectIndex.index(b),
                              bag.count(b)));
            }

        }

        Matrix srcMatrix = createMatrix(srcMat, srcIndex.getSize(), objectIndex.getSize());
        Matrix targetMatrix = createMatrix(targetMat, targetIndex.getSize(), objectIndex.getSize());

        Matrix sim = dotProduct(srcMatrix, targetMatrix);

        Vector srcDot = selfDot(srcMatrix);
        Vector targetDot = selfDot(targetMatrix);

        AMapping mapping = MappingFactory.createDefaultMapping();
        Object[] srcReverse = srcIndex.reverse();
        Object[] targetReverse = targetIndex.reverse();

        for(int i = 0; i < sim.numRows(); i++) {
            for(int j = 0; j < sim.numColumns(); j++){
                double similarity = sim.get(i, j)/Math.sqrt(srcDot.get(i) * targetDot.get(j));

                if(similarity >= threshold){
                    mapping.add((String)srcReverse[i], (String)targetReverse[j], similarity);
                }

            }
        }

        return mapping;
    }

    private FlexCompRowMatrix castOrTransform(Matrix matrix){
        if(matrix instanceof FlexCompRowMatrix){
            return (FlexCompRowMatrix)matrix;
        }else{
            return new FlexCompRowMatrix(matrix);
        }
    }

    private Vector selfDot(Matrix A){
        DenseVector vector = new DenseVector(A.numRows());

        FlexCompRowMatrix rA = castOrTransform(A);

        for (int a = 0; a < rA.numRows(); a++) {
            vector.set(a, rA.getRow(a).dot(new DenseVector(rA.getRow(a))));
        }

        return vector;
    }

    private FlexCompRowMatrix reshapeCol(FlexCompRowMatrix A, Matrix B){
        if(A.numColumns() < B.numColumns()){
            FlexCompRowMatrix out = new FlexCompRowMatrix(A.numRows(), B.numColumns());
            for(int r = 0; r < A.numRows(); r++){
                for(VectorEntry e: A.getRow(r)){
                    out.set(r, e.index(), e.get());
                }
            }
            return out;
        }
        return A;
    }

    private Matrix dotProduct(Matrix A, Matrix B){
        DenseMatrix out = new DenseMatrix(A.numRows(), B.numRows());
        FlexCompRowMatrix rA = reshapeCol(castOrTransform(A), B);
        FlexCompRowMatrix rB = reshapeCol(castOrTransform(B), A);

        for(int b = 0; b < rB.numRows(); b++){
            Vector bVec = rB.getRow(b);
            Vector oVec = new DenseVector(rA.numRows());
            oVec = rA.multAdd(1, bVec, oVec);

            for(VectorEntry e: oVec){
                out.set(e.index(), b, e.get());
            }
        }
        return out;
    }


    private Matrix createMatrix(List<MatrixEntries> entries, int dim0, int dim1){
        FlexCompRowMatrix matrix = new FlexCompRowMatrix(dim0, dim1);

        for(MatrixEntries e: entries)
            matrix.set(e.row, e.column, e.value);

        return matrix;
    }



    private class MatrixEntries {

        int row, column, value;

        public MatrixEntries(int row, int column, int value) {
            this.row = row;
            this.column = column;
            this.value = value;
        }

    }

}