build-native:
	scala-cli --power package --native-image \
		--scala-version 3.8.2 \
		--dependency com.github.alexarchambault::case-app:2.1.0 \
		--dependency io.github.memo33::scdbpf:0.3.0 \
		--main-class io.github.memo33.dbpfcli.Main \
		. -o ./lib/dbpf -f

clean:
	sbt clean
	rm -rf .scala-build/
	rm -rf lib/dbpf*
