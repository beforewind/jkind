package jkind.solvers.yices;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jkind.lustre.Type;
import jkind.lustre.values.Value;
import jkind.solvers.Model;

public class YicesModel extends Model {
	private final Map<String, String> aliases = new HashMap<>();
	private final Map<String, Value> values = new HashMap<>();

	public YicesModel(Map<String, Type> varTypes) {
		super(varTypes);
	}

	public void addAlias(String from, String to) {
		aliases.put(from, to);
	}

	public void addValue(String name, Value value) {
		values.put(name, value);
	}

	private String getAlias(String name) {
		String result = name;
		while (aliases.containsKey(result)) {
			result = aliases.get(result);
		}
		return result;
	}

	@Override
	public Value getValue(String name) {
		Value value = values.get(getAlias(name));
		if (value == null) {
			return getDefaultValue(varTypes.get(name));
		} else {
			return value;
		}
	}

	@Override
	public Set<String> getVariableNames() {
		Set<String> result = new HashSet<>();
		result.addAll(aliases.keySet());
		result.addAll(values.keySet());
		return result;
	}
}
