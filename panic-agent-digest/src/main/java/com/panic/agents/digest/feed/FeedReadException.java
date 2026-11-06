package com.panic.agents.digest.feed;

/** A single feed could not be read. Recoverable: the caller logs it and continues. */
public class FeedReadException extends RuntimeException {

	public FeedReadException(String message, Throwable cause) {
		super(message, cause);
	}
}
