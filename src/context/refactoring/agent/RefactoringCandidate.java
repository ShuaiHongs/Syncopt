package context.refactoring.agent;


public class RefactoringCandidate {
    private String judgment;          // "refactor" / "not_refactor"
    private String pattern;           // "atomic_replacement" / "concurrent_container" / ...
    private String reason;            // 中文原因
    private int confidence;           // 1~5
    private String implementationHint; // 可选的实现建议

    // 无参构造
    public RefactoringCandidate() {}

    // 全参构造
    public RefactoringCandidate(String judgment, String pattern, String reason, int confidence) {
        this.judgment = judgment;
        this.pattern = pattern;
        this.reason = reason;
        this.confidence = confidence;
    }

    // getter / setter
    public String getJudgment() { return judgment; }
    public void setJudgment(String judgment) { this.judgment = judgment; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public int getConfidence() { return confidence; }
    public void setConfidence(int confidence) { this.confidence = confidence; }
    public String getImplementationHint() { return implementationHint; }
    public void setImplementationHint(String implementationHint) { this.implementationHint = implementationHint; }

    @Override
    public String toString() {
        return "RefactoringCandidate{" +
                "judgment='" + judgment + '\'' +
                ", pattern='" + pattern + '\'' +
                ", reason='" + reason + '\'' +
                ", confidence=" + confidence +
                '}';
    }
}