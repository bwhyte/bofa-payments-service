package com.security.bank.cards;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.security.bank.dto.CardDto;
import com.security.bank.entity.Card;
import com.security.bank.service.CardService;

@RestController
@RequestMapping("/card")
public class UserCardController {

    @Autowired
    CardService cardService;

    @GetMapping("/block")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('CUSTOMER')")
    public String blockCard(@RequestParam Long accountNumber, @RequestParam Long cardNumber) {
	return cardService.blockCard(accountNumber, cardNumber);
    }

    @PostMapping("/apply/new")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public String applyNewCard(@RequestParam Long accountNumber, @RequestBody CardDto cardDto) {
	return cardService.applyNewCard(accountNumber, cardDto);
    }

    @PutMapping("/setting")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('CUSTOMER')")
    public void modifyLimit(@RequestBody Card card, @RequestParam Long cardNumber) {
	cardService.modifySetting(cardNumber, card);
    }

}
