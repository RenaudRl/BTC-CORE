package net.minecraft.server.permissions;

import java.util.function.Predicate;

public record PermissionProviderCheck<T extends PermissionSetSupplier>(PermissionCheck test) implements Predicate<T> {
    @Override
    public boolean test(T permissionsSupplier) {
        return this.test.check(permissionsSupplier.permissions());
    }
}
