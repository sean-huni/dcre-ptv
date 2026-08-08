package za.co.fnb.dcre.ptv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.ptv.data.model.TxEntryView;

import java.util.List;
import java.util.UUID;

public interface TxEntryViewRepo extends CrudRepository<TxEntryView, UUID> {

    /** Partition range load (R-41): sequence bounds inclusive. */
    List<TxEntryView> findByArrivalIdAndSequenceBetweenOrderBySequence(UUID arrivalId, int fromSeq, int toSeq);

    long countByArrivalId(UUID arrivalId);
}
