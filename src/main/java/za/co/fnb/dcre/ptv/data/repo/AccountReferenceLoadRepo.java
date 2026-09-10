package za.co.fnb.dcre.ptv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.ptv.data.model.AccountReferenceLoadEntity;

import java.util.UUID;

/** The append-only record of what each load consumed. Never updated, never deleted. */
public interface AccountReferenceLoadRepo extends CrudRepository<AccountReferenceLoadEntity, UUID> {
}
