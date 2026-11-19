package wellatleastitried.mediagarrdclient.api;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdclient.dto.PairRequest;
import wellatleastitried.mediagarrdclient.dto.PairingCodeDto;
import wellatleastitried.mediagarrdclient.dto.PairingResultDto;
import wellatleastitried.mediagarrdclient.dto.UnpairResultDto;
import wellatleastitried.mediagarrdclient.service.ClientPairingService;

@RestController
@RequestMapping("/api/client/pairing")
public class PairingController {

    private final ClientPairingService pairing;

    public PairingController(ClientPairingService pairing) {
        this.pairing = pairing;
    }

    @PostMapping
    public PairingResultDto pair(@RequestBody PairRequest request) {
        return pairing.pair(request.pin(), request.name());
    }

    @PostMapping("/code")
    public PairingCodeDto createCode() {
        return pairing.createCode();
    }

    @DeleteMapping
    public UnpairResultDto unpair() {
        return pairing.unpair();
    }
}
