# Instructions

This repository is a fork of the original RoadRunner implementation available at `https://github.com/stephenfreund/RoadRunner`.

This document augments instructions that are already provided by the unmodified RoadRunner framework. Check `RoadRunner-README.txt`, `INSTALL.txt`, and run `rrrun -help`.

## Invocation Examples

RoadRunner officially supports only till Java 1.8. I have not tested more recent Java versions, it is possible they work with RoadRunner.

In the following, I assume that the path to the RoadRunner directory is given by `$RR_HOME`. All instructions are relative to `$RR_HOME`, i.e., `cd $RR_HOME`.

- Export RoadRunner binary paths: `source msetup`. Do this once for every terminal invocation.
- Build: `ant` or `ant build`
- Clean: `ant clean`

  You might want to change the value of `-Xmx` and `availableProcessors` in the `msetup` script depending on your system configuration.

> Run FastTrack with microbenchmarks

Using the `rrun` script: `javac test/Test.java; rrrun -tool=FT2 -field=FINE -array=FINE -noTidGC -availableProcessors=4 test.Test`

Without using the `rrrun` script: `javac test/Test.java; java -javaagent:$RR_HOME/build/jar/rragent.jar -Xmx3g -Xms1g -Xloggc:/dev/stdout -Xbootclasspath/p:$RR_HOME/classes:$RR_HOME/jars/java-cup-11a.jar: rr.RRMain -tool=FT2 -field=FINE -array=FINE -noTidGC -availableProcessors=4 test.Test`

Provide the tool path: `java -javaagent:$RR_HOME/build/jar/rragent.jar -Xmx3g -Xms1g -Xloggc:/dev/stdout -Xbootclasspath/p:$RR_HOME/classes:$RR_HOME/jars/java-cup-11a.jar: rr.RRMain -tool=tools.fasttrack.FastTrackTool -field=FINE -array=FINE -noTidGC -availableProcessors=4 test.Test`

You can create new microbenchmarks like `test/Test.java` and run RoadRunner analyses on them.

## Benchmarks

RoadRunner supports a subset of popular Java benchmarks like [Java Grande](https://ieeexplore.ieee.org/document/1592782) and [DaCapo](http://dacapobench.org). The following benchmarks should work with RoadRunner.

    avrora
    fop
    h2
    jython
    luindex
    lusearch
    pmd
    sunflow
    tomcat
    xalan

> Run RoadRunner analyses with DaCapo benchmark `avrora`

      cd benchmarks/avrora

      ./TEST -tool=FT2 -array=FINE -field=FINE -noTidGC -availableProcessors=4

      rrrun -tool=FT2 -benchmark=10 -warmup=3

      ./TEST -tool=FT2 -array=FINE -field=FINE -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench

      rrrun -classpath=original.jar -tool=FT2 -array=FINE -field=FINE -noTidGC -noxml -availableProcessors=4 -benchmark=1 -warmup=0 Main -t 4 -s small

- Execute `avrora` with `java`:

      cd benchmarks/avrora

      java -javaagent:$RR_HOME/build/jar/rragent.jar -Xmx10g -Xbootclasspath/a:$RR_HOME/classes:$RR_HOME/jars/java-cup-11a.jar: rr.RRMain -classpath=$RR_HOME/benchmarks/avrora/original.jar -maxTid=14 -array=FINE -field=FINE -noTidGC -availableProcessors=4 -tool=FT2 -benchmark=1 -warmup=0 RRBench

- To execute `xalan` with `java`:

      cd benchmarks/xalan

      java -javaagent:$RR_HOME/build/jar/rragent.jar -Xmx10g -Xbootclasspath/p:$RR_HOME/classes:$RR_HOME/jars/java-cup-11a.jar: rr.RRMain -classpath=$RR_HOME/benchmarks/xalan/original.jar -maxTid=14 -array=FINE -field=FINE -noTidGC -availableProcessors=4 -tool=FT2 -benchmark=1 -warmup=0 RRBench

## Browsing the Source

Read the comments at the beginning of the `RRMain` class. The following is a list of a few important classes.

    ShadowThread
    ShadowLock
    FastTrackTool

## Notes

DaCapo Harness is not supported out of the box by RoadRunner, hence the following does not work.

    rrrun -classpath=<path-to-dacapo-9.12-bach.jar> -tool=FT2 -noTidGC -noxml Harness -t 4 -s small avrora

The following benchmarks also current seem to fail with unmodified RoadRunner.

    batik
    crypt
    lufact
    moldyn
    montecarlo
    raytracer
    series
    sor
    sparsematmult

It should be possible to resolve the issue with batik:

    https://stackoverflow.com/questions/1906673/import-com-sun-image-codec-jpeg

    https://www.stichlberger.com/software/workaround-for-batiks-noclassdeffounderrorclassnotfoundexception-truncatedfileexception/#codesyntax_1
