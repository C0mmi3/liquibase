package liquibase.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.database.OfflineConnection;
import liquibase.database.core.PostgresDatabase;
import liquibase.diff.compare.DatabaseObjectComparatorFactory;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.executor.ExecutorService;
import liquibase.servicelocator.ServiceLocator;
import liquibase.statement.core.RawSqlStatement;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;

import java.util.*;

public class SnapshotGeneratorFactory {

    private static SnapshotGeneratorFactory instance;

    private List<SnapshotGenerator> generators = new ArrayList<SnapshotGenerator>();

    protected SnapshotGeneratorFactory() {
        Class[] classes;
        try {
            classes = ServiceLocator.getInstance().findClasses(SnapshotGenerator.class);

            for (Class clazz : classes) {
                register((SnapshotGenerator) clazz.getConstructor().newInstance());
            }

        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);
        }

    }

    /**
     * Return singleton SnapshotGeneratorFactory
     */
    public static synchronized SnapshotGeneratorFactory getInstance() {
        if (instance == null) {
            instance = new SnapshotGeneratorFactory();
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = new SnapshotGeneratorFactory();
    }


    public void register(SnapshotGenerator generator) {
        generators.add(generator);
    }

    public void unregister(SnapshotGenerator generator) {
        generators.remove(generator);
    }

    public void unregister(Class generatorClass) {
        SnapshotGenerator toRemove = null;
        for (SnapshotGenerator existingGenerator : generators) {
            if (existingGenerator.getClass().equals(generatorClass)) {
                toRemove = existingGenerator;
            }
        }

        unregister(toRemove);
    }

    protected SortedSet<SnapshotGenerator> getGenerators(Class<? extends DatabaseObject> generatorClass, Database database) {
        SortedSet<SnapshotGenerator> validGenerators = new TreeSet<SnapshotGenerator>(new SnapshotGeneratorComparator(generatorClass, database));

        /*
         * Query all SnapshotGenerators if they consider themselves applicable for the generatorClass (e.g. a Table)
         * for a specific Database (e.g. MSSQL, Oracle, Postgres...)
         */
        for (SnapshotGenerator generator : generators) {
            if (generator.getPriority(generatorClass, database) > 0) {
                validGenerators.add(generator);
            }
        }
        return validGenerators;
    }


    /**
     * Checks if a specific object is present in a database
     * @param example The DatabaseObject to check for existence
     * @param database The DBMS in which the object might exist
     * @return true if object existence can be confirmed, false otherweise
     * @throws DatabaseException If a problem occurs in the DBMS-specific code
     * @throws InvalidExampleException If the object cannot be checked properly, e.g. if the object name is ambiguous
     */
    public boolean has(DatabaseObject example, Database database) throws DatabaseException, InvalidExampleException {
        // @todo I have seen duplicates in types - maybe convert the List into a Set? Need to understand it more thoroughly.
        List<Class<? extends DatabaseObject>> types = new ArrayList<Class<? extends DatabaseObject>>(getContainerTypes(example.getClass(), database));
        types.add(example.getClass());

        /*
         * Does the query concern the DATABASECHANGELOG / DATABASECHANGELOGLOCK table? If so, we do a quick & dirty
         * SELECT COUNT(*) on that table. If that works, we count that as confirmation of existence.
         */
        // @todo Actually, there may be extreme cases (distorted table statistics etc.) where a COUNT(*) might not be so cheap. Maybe SELECT a dummy constant is the better way?
        if (example instanceof Table &&
                (example.getName().equals(database.getDatabaseChangeLogTableName())
                        || example.getName().equals(database.getDatabaseChangeLogLockTableName()))) {
            try {
                ExecutorService.getInstance().getExecutor(database).queryForInt(
                        new RawSqlStatement("SELECT COUNT(*) FROM " +
                                database.escapeObjectName(database.getLiquibaseCatalogName(),
                                        database.getLiquibaseSchemaName(), example.getName(), Table.class)));
                return true;
            } catch (DatabaseException e) {
                if (database instanceof PostgresDatabase) { // throws "current transaction is aborted" unless we roll back the connection
                    database.rollback();
                }
                return false;
            }
        }

        /*
          * If the query is about another object, try to create a snapshot of the of the object (or used the cached
          * snapshot. If that works, we count that as confirmation of existence.
          */
        if (createSnapshot(example, database,
                new SnapshotControl(database, false, types.toArray(new Class[types.size()]))) != null) {
            return true;
        }
        CatalogAndSchema catalogAndSchema;
        if (example.getSchema() == null) {
            catalogAndSchema = database.getDefaultSchema();
        } else {
            catalogAndSchema = example.getSchema().toCatalogAndSchema();
        }
        DatabaseSnapshot snapshot = createSnapshot(catalogAndSchema, database, new SnapshotControl(database, false, example.getClass()));
        for (DatabaseObject obj : snapshot.get(example.getClass())) {
            if (DatabaseObjectComparatorFactory.getInstance().isSameObject(example, obj, null, database)) {
                return true;
            }
        }
        return false;
    }

    public DatabaseSnapshot createSnapshot(CatalogAndSchema example, Database database, SnapshotControl snapshotControl) throws DatabaseException, InvalidExampleException {
        return createSnapshot(new CatalogAndSchema[] {example}, database, snapshotControl);
    }

    public DatabaseSnapshot createSnapshot(CatalogAndSchema[] examples, Database database, SnapshotControl snapshotControl) throws DatabaseException, InvalidExampleException {
        if (database == null) {
            return null;
        }
        Schema[] schemas = new Schema[examples.length];
        for (int i = 0; i< schemas.length; i++) {
            examples[i] = examples[i].customize(database);
            schemas[i] = new Schema(examples[i].getCatalogName(), examples[i].getSchemaName());
        }
        return createSnapshot(schemas, database, snapshotControl);
    }

    public DatabaseSnapshot createSnapshot(DatabaseObject[] examples, Database database, SnapshotControl snapshotControl) throws DatabaseException, InvalidExampleException {
        if (database.getConnection() instanceof OfflineConnection) {
            DatabaseSnapshot snapshot = ((OfflineConnection) database.getConnection()).getSnapshot(examples);
            if (snapshot == null) {
                throw new DatabaseException("No snapshotFile parameter specified for offline database");
            }
            return snapshot;
        }
        return new JdbcDatabaseSnapshot(examples, database, snapshotControl);
    }

    public <T extends DatabaseObject> T createSnapshot(T example, Database database) throws DatabaseException, InvalidExampleException {
        return createSnapshot(example, database, new SnapshotControl(database));
    }

    public <T extends DatabaseObject> T createSnapshot(T example, Database database, SnapshotControl snapshotControl)
            throws DatabaseException, InvalidExampleException {
        DatabaseSnapshot snapshot = createSnapshot(new DatabaseObject[]{example}, database, snapshotControl);
        return snapshot.get(example);
    }

    public Table getDatabaseChangeLogTable(SnapshotControl snapshotControl, Database database) throws DatabaseException {
        try {
            Table liquibaseTable = (Table) new Table().setName(database.getDatabaseChangeLogTableName()).setSchema(new Schema(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName()));
            return createSnapshot(liquibaseTable, database, snapshotControl);
        } catch (InvalidExampleException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    public Table getDatabaseChangeLogLockTable(Database database) throws DatabaseException {
        try {
            Table example = (Table) new Table().setName(database.getDatabaseChangeLogLockTableName()).setSchema(new Schema(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName()));
            return createSnapshot(example, database);
        } catch (InvalidExampleException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    public boolean hasDatabaseChangeLogTable(Database database) throws DatabaseException {
        try {
            return has(new Table().setName(database.getDatabaseChangeLogTableName()).setSchema(new Schema(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName())), database);
        } catch (InvalidExampleException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    public boolean hasDatabaseChangeLogLockTable(Database database) throws DatabaseException {
        try {
            return has(new Table().setName(database.getDatabaseChangeLogLockTableName()).setSchema(new Schema(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName())), database);
        } catch (InvalidExampleException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    public static synchronized void resetAll() {
        instance = null;
    }

    public Set<Class<? extends DatabaseObject>> getContainerTypes(Class<? extends DatabaseObject> type, Database database) {
        Set<Class<? extends DatabaseObject>>  returnSet = new HashSet<Class<? extends DatabaseObject>>();

        getContainerTypes(type, database, returnSet);

        return returnSet;
    }

    private void getContainerTypes(Class<? extends DatabaseObject> type, Database database,
                                   Set<Class<? extends DatabaseObject>>  returnSet) {
        Class<? extends DatabaseObject>[] addsTo;

        // Have we already seen this type?
        if (!returnSet.add(type)) {
            return;
        }

        // Get a list of the SnapshotGenerators that are in charge of snapshotting
        // object type "type" in the DBMS "database"
        SortedSet<SnapshotGenerator> generators = getGenerators(type, database);

        if (generators != null && generators.size() > 0) {
            SnapshotGenerator generator = generators.iterator().next();
            addsTo = generator.addsTo();
            if (addsTo != null) {
                for (Class<? extends DatabaseObject> newType : addsTo) {
                    returnSet.add(newType);
                    getContainerTypes(newType, database, returnSet);
                }
            }
        }

    }
}
