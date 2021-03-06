package jkind.engines;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jkind.JKindException;
import jkind.JKindSettings;
import jkind.Main;
import jkind.Output;
import jkind.advice.Advice;
import jkind.advice.AdviceReader;
import jkind.advice.AdviceWriter;
import jkind.engines.invariant.GraphInvariantGenerationEngine;
import jkind.engines.messages.BaseStepMessage;
import jkind.engines.messages.EngineType;
import jkind.engines.messages.InductiveCounterexampleMessage;
import jkind.engines.messages.InvalidMessage;
import jkind.engines.messages.InvariantMessage;
import jkind.engines.messages.Itinerary;
import jkind.engines.messages.Message;
import jkind.engines.messages.MessageHandler;
import jkind.engines.messages.UnknownMessage;
import jkind.engines.messages.ValidMessage;
import jkind.engines.pdr.PdrEngine;
import jkind.lustre.EnumType;
import jkind.lustre.Expr;
import jkind.lustre.Type;
import jkind.lustre.values.EnumValue;
import jkind.lustre.values.IntegerValue;
import jkind.lustre.values.Value;
import jkind.results.Counterexample;
import jkind.results.Signal;
import jkind.results.layout.NodeLayout;
import jkind.slicing.ModelSlicer;
import jkind.solvers.Model;
import jkind.translation.Specification;
import jkind.util.StreamIndex;
import jkind.util.Util;
import jkind.writers.ConsoleWriter;
import jkind.writers.ExcelWriter;
import jkind.writers.Writer;
import jkind.writers.XmlWriter;

public class Director extends MessageHandler {
	private final JKindSettings settings;
	private final Specification spec;
	private final Writer writer;
	private final long startTime;

	private final List<String> remainingProperties = new ArrayList<>();
	private final List<String> validProperties = new ArrayList<>();
	private final List<String> invalidProperties = new ArrayList<>();
	private int baseStep = 0;
	private final Map<String, InductiveCounterexampleMessage> inductiveCounterexamples = new HashMap<>();

	private final List<Engine> engines = new ArrayList<>();
	private final List<Thread> threads = new ArrayList<>();

	private Advice inputAdvice;
	private AdviceWriter adviceWriter;

	public Director(JKindSettings settings, Specification spec) {
		this.settings = settings;
		this.spec = spec;

		this.writer = getWriter();
		this.startTime = System.currentTimeMillis();
		this.remainingProperties.addAll(spec.node.properties);

		if (settings.readAdvice != null) {
			this.inputAdvice = AdviceReader.read(settings.readAdvice);
		}

		if (settings.writeAdvice != null) {
			this.adviceWriter = new AdviceWriter(settings.writeAdvice);
			this.adviceWriter.addVarDecls(Util.getVarDecls(spec.node));
		}
	}

	private final Writer getWriter() {
		try {
			if (settings.excel) {
				return new ExcelWriter(settings.filename + ".xls", spec.node);
			} else if (settings.xml) {
				return new XmlWriter(settings.filename + ".xml", spec.typeMap, settings.xmlToStdout);
			} else {
				return new ConsoleWriter(new NodeLayout(spec.node));
			}
		} catch (IOException e) {
			throw new JKindException("Unable to open output file", e);
		}
	}

	public void run() {
		printHeader();
		writer.begin();
		addShutdownHook();
		createAndStartEngines();

		while (!timeout() && propertiesRemaining() && someThreadAlive() && !someEngineFailed()) {
			processMessages();
			sleep(100);
		}

		processMessages();
		if (removeShutdownHook()) {
			postProcessing();
			reportFailures();
		}
	}

	private void postProcessing() {
		writeUnknowns();
		writer.end();
		writeAdvice();
		printSummary();
	}

	private final Thread shutdownHook = new Thread("shutdown-hook") {
		@Override
		public void run() {
			Director.sleep(100);
			postProcessing();
		}
	};

	private void addShutdownHook() {
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	private boolean removeShutdownHook() {
		try {
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
			return true;
		} catch (IllegalStateException e) {
			// JVM already shutting down
			return false;
		}
	}

	private void createAndStartEngines() {
		createEngines();
		threads.forEach(Thread::start);
	}

	private void createEngines() {
		if (settings.boundedModelChecking) {
			addEngine(new BmcEngine(spec, settings, this));
		}

		if (settings.kInduction) {
			addEngine(new KInductionEngine(spec, settings, this));
		}

		if (settings.invariantGeneration) {
			addEngine(new GraphInvariantGenerationEngine(spec, settings, this));
		}

		if (settings.reduceInvariants) {
			addEngine(new InvariantReductionEngine(spec, settings, this));
		}

		if (settings.smoothCounterexamples) {
			addEngine(new SmoothingEngine(spec, settings, this));
		}

		if (settings.intervalGeneralization) {
			addEngine(new IntervalGeneralizationEngine(spec, settings, this));
		}

		if (settings.pdrMax > 0) {
			addEngine(new PdrEngine(spec, settings, this));
		}

		if (settings.readAdvice != null) {
			addEngine(new AdviceEngine(spec, settings, this, inputAdvice));
		}
	}

	private void addEngine(Engine engine) {
		engines.add(engine);
		threads.add(new Thread(engine, engine.getName()));
	}

	private static void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
		}
	}

	private boolean timeout() {
		long timeout = startTime + ((long) settings.timeout) * 1000;
		return System.currentTimeMillis() > timeout;
	}

	private boolean propertiesRemaining() {
		return !remainingProperties.isEmpty();
	}

	private boolean someThreadAlive() {
		return threads.stream().anyMatch(Thread::isAlive);
	}

	private boolean someEngineFailed() {
		return engines.stream().anyMatch(e -> e.getThrowable() != null);
	}

	private void writeUnknowns() {
		if (!remainingProperties.isEmpty()) {
			writer.writeUnknown(remainingProperties, baseStep, convertInductiveCounterexamples(),
					getRuntime());
		}
	}

	private void reportFailures() {
		for (Engine engine : engines) {
			if (engine.getThrowable() != null) {
				Output.println(engine.getName() + " process failed");
				Output.printStackTrace(engine.getThrowable());
			}
		}
	}

	private void printHeader() {
		if (!settings.xmlToStdout) {
			Output.println("==========================================");
			Output.println("  JKind " + Main.VERSION);
			Output.println("==========================================");
			Output.println();
			Output.println("There are " + remainingProperties.size() + " properties to be checked.");
			Output.println("PROPERTIES TO BE CHECKED: " + remainingProperties);
			Output.println();
		}
	}

	private void writeAdvice() {
		if (adviceWriter != null) {
			adviceWriter.write();
		}
	}

	public void broadcast(Message message) {
		receiveMessage(message);
		for (Engine engine : engines) {
			engine.receiveMessage(message);
		}
	}

	@Override
	protected void handleMessage(ValidMessage vm) {
		if (vm.getNextDestination() != null) {
			return;
		}

		List<String> newValid = intersect(vm.valid, remainingProperties);
		if (newValid.isEmpty()) {
			return;
		}

		remainingProperties.removeAll(newValid);
		validProperties.addAll(newValid);
		inductiveCounterexamples.keySet().removeAll(newValid);

		if (adviceWriter != null) {
			adviceWriter.addInvariants(vm.invariants);
		}

		List<Expr> invariants = settings.reduceInvariants ? vm.invariants : Collections.emptyList();
		writer.writeValid(newValid, vm.source, vm.k, getRuntime(), invariants);
	}

	private List<String> intersect(List<String> list1, List<String> list2) {
		List<String> result = new ArrayList<>();
		result.addAll(list1);
		result.retainAll(list2);
		return result;
	}

	@Override
	protected void handleMessage(InvalidMessage im) {
		if (im.getNextDestination() != null) {
			return;
		}

		List<String> newInvalid = intersect(im.invalid, remainingProperties);
		if (newInvalid.isEmpty()) {
			return;
		}

		remainingProperties.removeAll(newInvalid);
		invalidProperties.addAll(newInvalid);
		inductiveCounterexamples.keySet().removeAll(newInvalid);

		double runtime = getRuntime();
		for (String invalidProp : newInvalid) {
			Model slicedModel = ModelSlicer.slice(im.model, spec.dependencyMap.get(invalidProp));
			Counterexample cex = extractCounterexample(im.length, slicedModel);
			writer.writeInvalid(invalidProp, im.source, cex, runtime);
		}
	}

	@Override
	protected void handleMessage(InductiveCounterexampleMessage icm) {
		inductiveCounterexamples.put(icm.property, icm);
	}

	@Override
	protected void handleMessage(UnknownMessage um) {
		remainingProperties.removeAll(um.unknown);
		writer.writeUnknown(um.unknown, baseStep, convertInductiveCounterexamples(), getRuntime());
	}

	@Override
	protected void handleMessage(BaseStepMessage bsm) {
		baseStep = bsm.step;
		if (!bsm.properties.isEmpty()) {
			writer.writeBaseStep(bsm.properties, baseStep);
		}
	}

	@Override
	protected void handleMessage(InvariantMessage im) {
	}

	public Itinerary getValidMessageItinerary() {
		List<EngineType> destinations = new ArrayList<>();
		if (settings.reduceInvariants) {
			destinations.add(EngineType.INVARIANT_REDUCTION);
		}
		return new Itinerary(destinations);
	}

	public Itinerary getInvalidMessageItinerary() {
		List<EngineType> destinations = new ArrayList<>();
		if (settings.smoothCounterexamples) {
			destinations.add(EngineType.SMOOTHING);
		}
		if (settings.intervalGeneralization) {
			destinations.add(EngineType.INTERVAL_GENERALIZATION);
		}
		return new Itinerary(destinations);
	}

	private double getRuntime() {
		return (System.currentTimeMillis() - startTime) / 1000.0;
	}

	private void printSummary() {
		if (!settings.xmlToStdout) {
			Output.println("    -------------------------------------");
			Output.println("    --^^--        SUMMARY          --^^--");
			Output.println("    -------------------------------------");
			Output.println();
			if (!validProperties.isEmpty()) {
				Output.println("VALID PROPERTIES: " + validProperties);
				Output.println();
			}
			if (!invalidProperties.isEmpty()) {
				Output.println("INVALID PROPERTIES: " + invalidProperties);
				Output.println();
			}
			if (!remainingProperties.isEmpty()) {
				Output.println("UNKNOWN PROPERTIES: " + remainingProperties);
				Output.println();
			}
		}
	}

	private Map<String, Counterexample> convertInductiveCounterexamples() {
		Map<String, Counterexample> result = new HashMap<>();

		for (String prop : inductiveCounterexamples.keySet()) {
			InductiveCounterexampleMessage icm = inductiveCounterexamples.get(prop);
			Model slicedModel = ModelSlicer.slice(icm.model, spec.dependencyMap.get(icm.property));
			result.put(prop, extractCounterexample(icm.length, slicedModel));
		}

		return result;
	}

	private Counterexample extractCounterexample(int length, Model model) {
		Counterexample cex = new Counterexample(length);
		for (String var : model.getVariableNames()) {
			StreamIndex si = StreamIndex.decode(var);
			if (si.getIndex() >= 0 && !isInternal(si.getStream())) {
				Signal<Value> signal = cex.getOrCreateSignal(si.getStream());
				Value value = convert(si.getStream(), model.getValue(var));
				signal.putValue(si.getIndex(), value);
			}
		}
		return cex;
	}

	private boolean isInternal(String stream) {
		return stream.startsWith("%");
	}

	private Value convert(String base, Value value) {
		Type type = spec.typeMap.get(base);
		if (type instanceof EnumType) {
			EnumType et = (EnumType) type;
			IntegerValue iv = (IntegerValue) value;
			return new EnumValue(et.getValue(iv.value.intValue()));
		}
		return value;
	}
}
