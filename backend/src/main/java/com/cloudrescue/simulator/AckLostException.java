package com.cloudrescue.simulator;

/** The side effect WAS applied but the acknowledgement was lost: the caller cannot know the outcome. */
public class AckLostException extends RuntimeException {
    public AckLostException(String key) {
        super("Acknowledgement lost for operation " + key);
    }
}
