package com.logicaldoc.healdan.preview;

import java.io.IOException;

/**
 * LibreOffice could not be started at all: the command isn't allowed or
 * doesn't exist. Unlike a failed conversion, it says nothing about the file.
 */
public class LibreOfficeUnavailableException extends IOException {

	private static final long serialVersionUID = 1L;

	public LibreOfficeUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
