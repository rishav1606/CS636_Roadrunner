# Instructions

This repository is a fork of the original RoadRunner implementation available at `https://github.com/stephenfreund/RoadRunner`.

**NOTE**: This document *augments* instructions that are already provided by the unmodified RoadRunner framework. Check `README-RoadRunner.txt`, `INSTALL.txt`, and run `rrrun -help`.

## Invocation Examples

RoadRunner currently supports only till Java 1.8.

In the following, I assume that the path to the RoadRunner directory is given by $RR_HOME. All instructions are relative to `$RR_HOME`, i.e., `cd $RR_HOME`.

+ Build: `ant`

+ Clean: `ant clean`

+ Export RoadRunner binary paths: `source msetup`. Do this once for every terminal invocation.

    You might want to change the value of `-Xmx` and `availbleProcessors` in the `msetup` script depending on your system configuration.

+ Run FastTrack with microbenchmarks: `javac test/Test.java; rrrun -tool=FT2 -noTidGC test.Test`

    It should be possible to create standalone examples like `test/Test.java` and run RoadRunner analyses on them.

## Benchmarks

RoadRunner currently supports a subset of popular Java benchmarks like Java Grande and [DaCapo](http://dacapobench.org). The following benchmarks should work with RoadRunner.

    avrora
    batik
    fop
    h2
    jython
    luindex
    lusearch
    pmd
    sunflow
    tomcat
    xalan

+ Some examples on how to run RoadRunner analyses with DaCapo benchmark `avrora`:

        cd benchmarks/avrora

        ./TEST -tool=FT2 -array=FINE -field=FINE -noTidGC -availableProcessors=4

        rrrun -tool=FT2 -benchmark=10 -warmup=3

        ./TEST -tool=FT2 -array=FINE -field=FINE -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench

        rrrun -classpath=original.jar -tool=FT2 -array=FINE -field=FINE -noTidGC -noxml -availableProcessors=4 -benchmark=1 -warmup=0 Main -t 4 -s small

+ Execute `avrora` with `java`:

        cd benchmarks/avrora

        java -javaagent:$RR_HOME/build/jar/rragent.jar -Xmx10g -Xbootclasspath/a:$RR_HOME/classes:$RR_HOME/jars/java-cup-11a.jar: rr.RRMain -classpath=$RR_HOME/benchmarks/avrora/original.jar -maxTid=14 -array=FINE -field=FINE -noTidGC -availableProcessors=4 -tool=FT2 -benchmark=1 -warmup=0 RRBench

+ To execute `xalan` with `java`:

        cd benchmarks/xalan

        /usr/lib/jvm/java-8-openjdk-amd64/bin/java -javaagent:$RR_HOME/build/jar/rragent.jar -Xmx10g -Xbootclasspath/p:$RR_HOME/classes:$RR_HOME/jars/java-cup-11a.jar: rr.RRMain -classpath=$RR_HOME/benchmarks/xalan/original.jar -maxTid=14 -array=FINE -field=FINE -noTidGC -availableProcessors=4 -tool=FT2 -benchmark=1 -warmup=0 RRBench


## Browsing the Source

Read the comments at the beginning of the `RRMain` class. The following is a list of a few important classes.

    ShadowThread
    ShadowLock
    FastTrackTool

## Notes

DaCapo Harness is not supported out of the box by RoadRunner, hence the following does not work.

    rrrun -classpath=/home/swarnendu/iitk-workspace/roadrunner/benchmarks/dacapo-9.12-bach.jar -tool=FT2 -noTidGC -noxml Harness -t 4 -s small avrora

The following benchmarks also current seem to fail with unmodified RoadRunner.

    crypt
    lufact
    moldyn
    montecarlo
    raytracer
    series
    sor
    sparsematmult
