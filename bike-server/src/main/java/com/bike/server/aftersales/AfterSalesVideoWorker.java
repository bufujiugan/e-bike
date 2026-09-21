package com.bike.server.aftersales;

import java.nio.file.Path;

/** Dedicated command-line entry point; it never starts Spring or connects to application services. */
public final class AfterSalesVideoWorker {
    public static final String ARGUMENT = "--bike-validate-video";
    private AfterSalesVideoWorker() {}

    public static void main(String[] args) {
        int result = 2;
        try {
            if (args.length == 4) {
                var validator = new AfterSalesVideoValidator(Long.parseLong(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                validator.validateInProcess(Path.of(args[0]));
                result = 0;
            }
        } catch (Exception error) {
            // No filesystem paths, media content or application configuration are returned to the caller.
        }
        System.exit(result);
    }
}
