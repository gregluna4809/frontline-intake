package com.frontline.customer;

import com.frontline.model.Customer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerRepository repository;

    public CustomerController(CustomerRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerRepository.CreatedCustomer create(@Valid @RequestBody NewCustomer input) {
        return repository.create(input.displayName().strip(), input.language(), input.veteran());
    }

    public record NewCustomer(@NotBlank @Size(max = 100) String displayName,
                              @NotNull Customer.Language language, @NotNull Boolean veteran) {}

    @GetMapping
    public List<Customer> search(@RequestParam(defaultValue = "") String q) {
        return repository.search(q);
    }
}
