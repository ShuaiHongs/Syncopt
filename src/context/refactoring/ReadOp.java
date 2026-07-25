package context.refactoring;

public class ReadOp extends OpNode {
    public String variable;
    public boolean isFieldRead;

    public ReadOp(int line, String variable, boolean isFieldRead) {
        super(line);
        this.variable = variable;
        this.isFieldRead = isFieldRead;
    }
}