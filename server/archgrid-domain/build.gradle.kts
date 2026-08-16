// The domain module deliberately declares no dependencies beyond the JDK and JSpecify
// nullability annotations.
//
// This is not minimalism for its own sake: the metamodel, the graph and the command
// validation must be testable without a Spring context and without a database, because
// they are the part of the system that has to be provably correct (see ADR-0002).
//
// Adding Spring, Jackson, JPA or java.sql here is a design error, not a convenience.
// ArchUnit re-checks this in archgrid-app, so the rule survives even if someone
// later merges the modules.
