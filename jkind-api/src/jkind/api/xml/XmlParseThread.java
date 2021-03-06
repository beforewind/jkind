package jkind.api.xml;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import jkind.JKindException;
import jkind.api.Backend;
import jkind.api.results.JKindResult;
import jkind.api.results.PropertyResult;
import jkind.interval.IntEndpoint;
import jkind.interval.Interval;
import jkind.interval.NumericEndpoint;
import jkind.interval.NumericInterval;
import jkind.interval.RealEndpoint;
import jkind.lustre.values.IntegerValue;
import jkind.lustre.values.RealValue;
import jkind.lustre.values.Value;
import jkind.results.Counterexample;
import jkind.results.InvalidProperty;
import jkind.results.Property;
import jkind.results.Signal;
import jkind.results.UnknownProperty;
import jkind.results.ValidProperty;
import jkind.util.Util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class XmlParseThread extends Thread {
	private final InputStream xmlStream;
	private final JKindResult result;
	private final Backend backend;
	private final DocumentBuilderFactory factory;
	private volatile Throwable throwable;

	public XmlParseThread(InputStream xmlStream, JKindResult result, Backend backend) {
		super("Xml Parse");
		this.xmlStream = xmlStream;
		this.result = result;
		this.backend = backend;
		this.factory = DocumentBuilderFactory.newInstance();
	}

	@Override
	public void run() {
		/*
		 * XML parsers buffer their input which conflicts with the way we are
		 * streaming data from the XML file as it is written. This results in
		 * data in the XML not being acted upon until more content is written to
		 * the XML file which causes the buffer to fill. Instead, we read the
		 * XML file ourselves and give relevant pieces of it to the parser as
		 * they are ready.
		 * 
		 * The downside is we assume the <Property ...> and </Property> tags are
		 * on their own lines.
		 */

		try (LineInputStream lines = new LineInputStream(xmlStream)) {
			StringBuilder buffer = null;
			String line;
			while ((line = lines.readLine()) != null) {
				boolean beginProperty = line.contains("<Property ");
				boolean endProperty = line.contains("</Property>");
				boolean beginProgress = line.contains("<Progress ");
				boolean endProgress = line.contains("</Progress>");

				if (beginProgress && endProgress) {
					// Kind 2 progress format uses a single line
					parseKind2ProgressXml(line);
				} else if (beginProgress || beginProperty) {
					buffer = new StringBuilder();
					buffer.append(line);
				} else if (endProperty) {
					buffer.append(line);
					parsePropetyXml(buffer.toString());
					buffer = null;
				} else if (endProgress) {
					buffer.append(line);
					parseJKindProgressXml(buffer.toString());
					buffer = null;
				} else if (buffer != null) {
					buffer.append(line);
				}
			}
		} catch (Throwable t) {
			throwable = t;
		}
	}

	private Element parseXml(String xml) {
		try {
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(new InputSource(new StringReader(xml)));
			return doc.getDocumentElement();
		} catch (Exception e) {
			throw new JKindException("Error parsing: " + xml, e);
		}
	}

	private void parseKind2ProgressXml(String progressXml) {
		Element progressElement = parseXml(progressXml);
		String source = progressElement.getAttribute("source");
		if ("bmc".equals(source)) {
			int k = Integer.parseInt(progressElement.getTextContent());
			result.setBaseProgress(k);
		}
	}

	private void parseJKindProgressXml(String progressXml) {
		Element progressElement = parseXml(progressXml);
		String source = progressElement.getAttribute("source");
		if ("bmc".equals(source)) {
			int trueFor = Integer.parseInt(progressElement.getAttribute("trueFor"));
			for (Element propertyElement : getElements(progressElement, "PropertyProgress")) {
				String prop = propertyElement.getAttribute("name");
				PropertyResult pr = result.getPropertyResult(prop);
				if (pr != null) {
					pr.setBaseProgress(trueFor);
				}
			}
		}
	}

	public void parsePropetyXml(String propertyXml) {
		Property prop = getProperty(parseXml(propertyXml));
		PropertyResult pr = result.getPropertyResult(prop.getName());
		if (pr == null) {
			pr = result.addProperty(prop.getName());
			if (pr == null) {
				return;
			}
		}
		pr.setProperty(prop);
	}

	private Property getProperty(Element propertyElement) {
		String name = propertyElement.getAttribute("name");
		double runtime = getRuntime(getElement(propertyElement, "Runtime"));
		int trueFor = getTrueFor(getElement(propertyElement, "TrueFor"));
		int k = getK(getElement(propertyElement, "K"));
		String answer = getAnswer(getElement(propertyElement, "Answer"));
		String source = getSource(getElement(propertyElement, "Answer"));
		List<String> invariants = getInvariants(getElements(propertyElement, "Invariant"));
		Counterexample cex = getCounterexample(getElement(propertyElement, "Counterexample"), k);

		switch (answer) {
		case "valid":
			return new ValidProperty(name, source, k, runtime, invariants);

		case "falsifiable":
			return new InvalidProperty(name, source, cex, runtime);

		case "unknown":
			return new UnknownProperty(name, trueFor, cex, runtime);

		default:
			throw new JKindException("Unknown property answer in XML file: " + answer);
		}
	}

	private double getRuntime(Node runtimeNode) {
		if (runtimeNode == null) {
			return 0;
		}
		return Double.parseDouble(runtimeNode.getTextContent());
	}

	private int getTrueFor(Node trueForNode) {
		if (trueForNode == null) {
			return 0;
		}
		return Integer.parseInt(trueForNode.getTextContent());
	}

	private int getK(Node kNode) {
		if (kNode == null) {
			return 0;
		}
		return Integer.parseInt(kNode.getTextContent());
	}

	private String getAnswer(Node answerNode) {
		return answerNode.getTextContent();
	}

	private String getSource(Element answerNode) {
		return answerNode.getAttribute("source");
	}

	private List<String> getInvariants(List<Element> invariantElements) {
		List<String> invariants = new ArrayList<>();
		for (Element invariantElement : invariantElements) {
			invariants.add(invariantElement.getTextContent());
		}
		return invariants;
	}

	private Counterexample getCounterexample(Element cexElement, int k) {
		if (cexElement == null) {
			return null;
		}

		Counterexample cex = new Counterexample(k);
		for (Element signalElement : getElements(cexElement, getSignalTag())) {
			cex.addSignal(getSignal(signalElement));
		}
		return cex;
	}

	protected String getSignalTag() {
		switch (backend) {
		case JKIND:
			return "Signal";
		case KIND2:
			return "Stream";
		default:
			throw new IllegalArgumentException();
		}
	}

	private Signal<Value> getSignal(Element signalElement) {
		String name = signalElement.getAttribute("name");
		String type = signalElement.getAttribute("type");
		if (type.contains("subrange ")) {
			type = "int";
		}

		Signal<Value> signal = new Signal<>(name);
		for (Element valueElement : getElements(signalElement, "Value")) {
			int time = Integer.parseInt(valueElement.getAttribute(getTimeAttribute()));
			signal.putValue(time, getValue(valueElement, type));
		}
		return signal;
	}

	protected String getTimeAttribute() {
		switch (backend) {
		case JKIND:
			return "time";
		case KIND2:
			return "instant";
		default:
			throw new IllegalArgumentException();
		}
	}

	private Value getValue(Element valueElement, String type) {
		Element intervalElement = getElement(valueElement, "Interval");
		if (intervalElement != null) {
			return getIntervalValue(intervalElement, type);
		}

		return Util.parseValue(type, valueElement.getTextContent());
	}

	private Interval getIntervalValue(Element intervalElement, String type) {
		String low = intervalElement.getAttribute("low");
		String high = intervalElement.getAttribute("high");
		NumericEndpoint lowEnd;
		NumericEndpoint highEnd;

		switch (type) {
		case "int":
			lowEnd = readIntEndpoint(low);
			highEnd = readIntEndpoint(high);
			break;

		case "real":
			lowEnd = readRealEndpoint(low);
			highEnd = readRealEndpoint(high);
			break;

		default:
			throw new JKindException("Unknown interval type in XML file: " + type);
		}

		return new NumericInterval(lowEnd, highEnd);
	}

	private IntEndpoint readIntEndpoint(String text) {
		switch (text) {
		case "inf":
			return IntEndpoint.POSITIVE_INFINITY;
		case "-inf":
			return IntEndpoint.NEGATIVE_INFINITY;
		default:
			IntegerValue iv = (IntegerValue) Util.parseValue("int", text);
			return new IntEndpoint(iv.value);
		}
	}

	private RealEndpoint readRealEndpoint(String text) {
		switch (text) {
		case "inf":
			return RealEndpoint.POSITIVE_INFINITY;
		case "-inf":
			return RealEndpoint.NEGATIVE_INFINITY;
		default:
			RealValue rv = (RealValue) Util.parseValue("real", text);
			return new RealEndpoint(rv.value);
		}
	}

	private Element getElement(Element element, String name) {
		return (Element) element.getElementsByTagName(name).item(0);
	}

	private List<Element> getElements(Element element, String name) {
		List<Element> elements = new ArrayList<>();
		NodeList nodeList = element.getElementsByTagName(name);
		for (int i = 0; i < nodeList.getLength(); i++) {
			elements.add((Element) nodeList.item(i));
		}
		return elements;
	}

	public Throwable getThrowable() {
		return throwable;
	}
}
