DIR=~/jsglr2evaluation

all: languages sources validate measurements benchmarks

languages:
	amm setupLanguages.sc $(DIR)

sources:
	amm setupSources.sc $(DIR)

validate:
	JAVA_OPTS="-Xmx8G" amm validateSources.sc $(DIR)

measurements: buildMeasurements execMeasurements

buildMeasurements:
	mvn -f ../org.spoofax.jsglr2.measure -q clean install

execMeasurements:
	amm measurements.sc $(DIR)

benchmarks: buildBenchmarks execBenchmarks

buildBenchmarks:
	mvn -f ../org.spoofax.jsglr2.benchmark -q clean install

execBenchmarks:
	amm benchmarks.sc $(DIR)