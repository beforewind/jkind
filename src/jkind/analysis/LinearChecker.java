package jkind.analysis;

import jkind.lustre.BinaryExpr;
import jkind.lustre.BoolExpr;
import jkind.lustre.Equation;
import jkind.lustre.Expr;
import jkind.lustre.ExprVisitor;
import jkind.lustre.IdExpr;
import jkind.lustre.IfThenElseExpr;
import jkind.lustre.IntExpr;
import jkind.lustre.Node;
import jkind.lustre.RealExpr;
import jkind.lustre.UnaryExpr;

public class LinearChecker implements ExprVisitor<Void> {
	private boolean passed;
	private ConstantAnalyzer constantAnalyzer;

	public LinearChecker() {
		this.passed = true;
	}

	public static boolean check(Node node) {
		return new LinearChecker().visitNode(node);
	}

	public boolean visitNode(Node node) {
		constantAnalyzer = new ConstantAnalyzer(node);
		
		for (Equation eq : node.equations) {
			eq.expr.accept(this);
		}

		return passed;
	}

	@Override
	public Void visit(BinaryExpr e) {
		e.left.accept(this);
		e.right.accept(this);
		
		switch (e.op) {
		case MULTIPLY:
		case DIVIDE:
		case INT_DIVIDE:
			if (!isConstant(e.left) && !isConstant(e.right)) {
				System.out.println("Nonlinearity error at line " + e.location);
				passed = false;
			}
		}
		
		return null;
	}

	private boolean isConstant(Expr e) {
		return e.accept(constantAnalyzer);
	}

	@Override
	public Void visit(BoolExpr e) {
		return null;
	}

	@Override
	public Void visit(IdExpr e) {
		return null;
	}

	@Override
	public Void visit(IfThenElseExpr e) {
		e.cond.accept(this);
		e.thenExpr.accept(this);
		e.elseExpr.accept(this);
		return null;
	}

	@Override
	public Void visit(IntExpr e) {
		return null;
	}

	@Override
	public Void visit(RealExpr e) {
		return null;
	}

	@Override
	public Void visit(UnaryExpr e) {
		e.expr.accept(this);
		return null;
	}
}
