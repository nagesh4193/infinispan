package org.infinispan.tools.store.migrator.rocksdb;

import static org.infinispan.tools.store.migrator.Element.CACHE_NAME;
import static org.infinispan.tools.store.migrator.Element.COMPRESSION;
import static org.infinispan.tools.store.migrator.Element.LOCATION;

import java.io.File;
import java.util.Iterator;

import org.infinispan.commons.CacheException;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.marshall.persistence.impl.MarshalledEntryFactoryImpl;
import org.infinispan.metadata.InternalMetadata;
import org.infinispan.persistence.spi.MarshallableEntry;
import org.infinispan.persistence.spi.MarshallableEntryFactory;
import org.infinispan.persistence.spi.PersistenceException;
import org.infinispan.tools.store.migrator.StoreIterator;
import org.infinispan.tools.store.migrator.StoreProperties;
import org.infinispan.tools.store.migrator.marshaller.SerializationConfigUtil;
import org.infinispan.tools.store.migrator.marshaller.common.MarshalledEntryImpl;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

public class RocksDBReader implements StoreIterator {

   private final RocksDB db;
   private final Marshaller marshaller;
   private final MarshallableEntryFactory entryFactory;

   public RocksDBReader(StoreProperties props) {
      props.required(LOCATION);
      String location = props.get(LOCATION) + props.get(CACHE_NAME).replaceAll("[^a-zA-Z0-9-_\\.]", "_");
      File f = new File(location);
      if (!f.exists() || !f.isDirectory())
         throw new CacheException(String.format("Unable to read db directory '%s'", location));

      Options options = new Options().setCreateIfMissing(false);
      String compressionType = props.get(COMPRESSION);
      if (compressionType != null) {
         options.setCompressionType(CompressionType.getCompressionType(compressionType));
      }

      try {
         this.db = RocksDB.openReadOnly(options, location);
      } catch (RocksDBException e) {
         throw new CacheException(e);
      }
      this.marshaller = SerializationConfigUtil.getMarshaller(props);
      this.entryFactory = new MarshalledEntryFactoryImpl(marshaller);
   }

   @Override
   public void close() {
      db.close();
   }

   @Override
   public Iterator<MarshallableEntry> iterator() {
      return new RocksDBIterator();
   }

   class RocksDBIterator implements Iterator<MarshallableEntry>, AutoCloseable {

      final RocksIterator it;

      private RocksDBIterator() {
         this.it = db.newIterator(new ReadOptions().setFillCache(false));
         it.seekToFirst();
      }

      @Override
      public void close() {
         it.close();
      }

      @Override
      public boolean hasNext() {
         return it.isValid();
      }

      @Override
      public MarshallableEntry next() {
         Object entry = unmarshall(it.value());
         if (entry instanceof MarshalledEntryImpl) {
            MarshalledEntryImpl me = (MarshalledEntryImpl) entry;
            InternalMetadata meta = me.getMetadata();
            long created = meta != null ? meta.created() : -1;
            long lifespan = meta != null ? meta.lifespan() : -1;
            entry = entryFactory.create(me.getKeyBytes(), me.getValueBytes(), me.getMetadataBytes(), null, created, lifespan);
         }
         it.next();
         return (MarshallableEntry) entry;
      }

      @SuppressWarnings(value = "unchecked")
      private <T> T unmarshall(byte[] bytes) {
         try {
            return (T) marshaller.objectFromByteBuffer(bytes);
         } catch (Exception e) {
            throw new PersistenceException(e);
         }
      }
   }
}
