package vn.edu.huit.finance.core.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.huit.finance.core.Service.Storage.FileStorageService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    @Autowired
    private FileStorageService fileStorageService;

    @PostMapping
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            System.out.println("SERVER ĐÃ NHẬN ĐƯỢC ẢNH TỪ APP: " + file.getOriginalFilename());
            String fileUrl = fileStorageService.uploadFile(file);

            if (fileUrl == null || fileUrl.isEmpty()) {
                System.out.println("LỖI CLOUDINARY: Không thể tải ảnh lên (Sai Key hoặc hết dung lượng)");
                return ResponseEntity.status(500).body(Map.of("message", "Lỗi Cloudinary"));
            }

            System.out.println("UPLOAD THÀNH CÔNG! Link ảnh: " + fileUrl);

            Map<String, String> response = new HashMap<>();
            response.put("url", fileUrl);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            System.out.println("LỖI SERVER KHI UPLOAD ẢNH: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", e.getMessage()));
        }
    }
}