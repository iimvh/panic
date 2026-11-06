package com.panic.exception;

import java.net.HttpURLConnection;

/** Domain guard rejection; carries the HTTP status the API surface should answer with. */
public class BookingException extends RuntimeException {

	private final int status;

	/** Accepted by callers that treat any guard failure as a bad request. */
	public BookingException(String message, int status) {
		super(message);
		this.status = status;
	}

	public BookingException(String message) {
		this(message, HttpURLConnection.HTTP_BAD_REQUEST);
	}

	/** HTTP status answering the request that tripped this guard (400 unless stated). */
	public int status() {
		return status;
	}
}
