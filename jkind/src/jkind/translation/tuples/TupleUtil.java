package jkind.translation.tuples;

import java.util.ArrayList;
import java.util.List;

import jkind.lustre.BinaryExpr;
import jkind.lustre.BinaryOp;
import jkind.lustre.Expr;
import jkind.lustre.IfThenElseExpr;
import jkind.lustre.TupleExpr;
import jkind.lustre.UnaryExpr;
import jkind.lustre.UnaryOp;

public class TupleUtil {
	public static TupleExpr mapBinary(BinaryOp op, TupleExpr expr1, TupleExpr expr2) {
		return new TupleExpr(zipWithBinary(op, expr1.elements, expr2.elements));
	}

	private static List<Expr> zipWithBinary(BinaryOp op, List<Expr> exprs1, List<Expr> exprs2) {
		List<Expr> result = new ArrayList<>();
		for (int i = 0; i < exprs1.size(); i++) {
			result.add(new BinaryExpr(exprs1.get(i), op, exprs2.get(i)));
		}
		return result;
	}
	
	public static TupleExpr mapIf(Expr cond, TupleExpr expr1, TupleExpr expr2) {
		return new TupleExpr(zipWithIf(cond, expr1.elements, expr2.elements));
	}

	private static List<Expr> zipWithIf(Expr cond, List<Expr> exprs1, List<Expr> exprs2) {
		List<Expr> result = new ArrayList<>();
		for (int i = 0; i < exprs1.size(); i++) {
			result.add(new IfThenElseExpr(cond, exprs1.get(i), exprs2.get(i)));
		}
		return result;
	}

	public static TupleExpr mapUnary(UnaryOp op, TupleExpr expr) {
		return new TupleExpr(mapUnary(op, expr.elements));
	}
	
	public static List<Expr> mapUnary(UnaryOp op, List<Expr> exprs) {
		List<Expr> result = new ArrayList<>();
		for (int i = 0; i < exprs.size(); i++) {
			result.add(new UnaryExpr(op, exprs.get(i)));
		}
		return result;
	}
}
