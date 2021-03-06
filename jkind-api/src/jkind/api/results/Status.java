package jkind.api.results;

public enum Status {
	WORKING, VALID, INVALID, UNKNOWN, CANCELED, ERROR, WAITING;

	@Override
	public String toString() {
		switch (this) {
		case WORKING:
			return "Working";
		case VALID:
			return "Valid";
		case INVALID:
			return "Invalid";
		case UNKNOWN:
			return "Unknown";
		case CANCELED:
			return "Canceled";
		case ERROR:
			return "Error";
		case WAITING:
			return "Waiting";
		default:
			return "";
		}
	}
}
