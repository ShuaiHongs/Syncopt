package context.refactoring;

public class WriteOp extends OpNode {
    public String variable;
    public boolean isFieldWrite;
    public boolean isCollectionWrite;
    public boolean isArrayWrite;

    public WriteOp(int line, String variable, boolean isFieldWrite, boolean isCollectionWrite, boolean isArrayWrite) {
        super(line);
        this.variable = variable;
        this.isFieldWrite = isFieldWrite;
        this.isCollectionWrite = isCollectionWrite;
        this.isArrayWrite = isArrayWrite;
    }
}